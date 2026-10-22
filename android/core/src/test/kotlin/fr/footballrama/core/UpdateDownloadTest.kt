package fr.footballrama.core

import fr.footballrama.core.update.ApkDownloader
import fr.footballrama.core.update.AppRelease
import fr.footballrama.core.update.FileIntegrity
import fr.footballrama.core.update.UpdateDownloadException
import fr.footballrama.core.update.UpdateDownloadException.Kind
import fr.footballrama.core.update.UpdateManifestClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest

/** Telechargement de update.json et de l'APK (MockWebServer, aucun appel reseau reel). */
class UpdateDownloadTest {

    private lateinit var server: MockWebServer

    @TempDir
    lateinit var tmp: File

    private val apkBytes = ByteArray(200_000) { (it * 31 % 251).toByte() }
    private val apkSha = sha256Hex(apkBytes)

    /** En test : http local autorise, hote « localhost » autorise. */
    private val downloader = ApkDownloader(allowCleartextForTests = true, allowedHost = { it == "localhost" || it == "127.0.0.1" })

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() = server.close()

    private fun release(url: HttpUrl = server.url("/dl/app.apk"), sha: String = apkSha, size: Long = apkBytes.size.toLong()) =
        AppRelease(versionCode = 2, versionName = "1.0.1", apkUrl = url, sha256 = sha, size = size, minSdk = 26, notes = "")

    private fun body(bytes: ByteArray) = MockResponse.Builder().body(Buffer().write(bytes)).build()

    @Test
    fun `telechargement verifie - taille et sha256 corrects, progression`() {
        server.enqueue(body(apkBytes))
        val dest = File(tmp, "update.apk")
        var last = 0L
        downloader.download(release(), dest) { last = it }
        assertArrayEquals(apkBytes, dest.readBytes())
        assertEquals(apkBytes.size.toLong(), last)
        assertTrue(FileIntegrity.matches(dest, apkBytes.size.toLong(), apkSha))
    }

    @Test
    fun `sha256 different - fichier supprime`() {
        server.enqueue(body(apkBytes))
        val dest = File(tmp, "update.apk")
        val wrong = sha256Hex("autre".toByteArray())
        assertEquals(Kind.HASH_MISMATCH, assertThrows<UpdateDownloadException> { downloader.download(release(sha = wrong), dest) }.kind)
        assertFalse(dest.exists())
    }

    @Test
    fun `taille differente du manifeste - refus et fichier supprime`() {
        val dest = File(tmp, "update.apk")
        // Plus long que la taille signee (Content-Length annonce) -> TOO_LARGE sans lecture complete.
        server.enqueue(body(apkBytes + ByteArray(10)))
        assertEquals(Kind.TOO_LARGE, assertThrows<UpdateDownloadException> { downloader.download(release(), dest) }.kind)
        assertFalse(dest.exists())
        // Plus court.
        server.enqueue(body(apkBytes.copyOf(1000)))
        assertEquals(Kind.SIZE_MISMATCH, assertThrows<UpdateDownloadException> { downloader.download(release(), dest) }.kind)
        assertFalse(dest.exists())
        // Sans Content-Length (chunked) et trop long : coupe a la taille signee.
        server.enqueue(MockResponse.Builder().chunkedBody(Buffer().write(apkBytes + ByteArray(10)), 4096).build())
        assertEquals(Kind.TOO_LARGE, assertThrows<UpdateDownloadException> { downloader.download(release(), dest) }.kind)
        assertFalse(dest.exists())
    }

    @Test
    fun `taille annoncee au-dela du maximum refusee sans requete`() {
        val dest = File(tmp, "update.apk")
        val r = release(size = 100L * 1024 * 1024 + 1)
        assertEquals(Kind.TOO_LARGE, assertThrows<UpdateDownloadException> { downloader.download(r, dest) }.kind)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `HTTP impose hors tests et redirection vers un hote non autorise refusee`() {
        val strict = ApkDownloader()
        assertEquals(
            Kind.NOT_HTTPS,
            assertThrows<UpdateDownloadException> { strict.download(release(), File(tmp, "a.apk")) }.kind,
        )
        assertEquals(0, server.requestCount)

        // Redirection (comme github.com -> objects.githubusercontent.com) vers un hote hors liste : refusee.
        val onlyLocalhost = ApkDownloader(allowCleartextForTests = true, allowedHost = { it == server.hostName })
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "http://evil.invalid/app.apk").build())
        val dest = File(tmp, "b.apk")
        assertEquals(Kind.NOT_HTTPS, assertThrows<UpdateDownloadException> { onlyLocalhost.download(release(), dest) }.kind)
        assertFalse(dest.exists())
    }

    @Test
    fun `redirection vers un hote autorise suivie`() {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", server.url("/cdn/app.apk").toString()).build())
        server.enqueue(body(apkBytes))
        val dest = File(tmp, "update.apk")
        downloader.download(release(), dest)
        assertTrue(FileIntegrity.matches(dest, apkBytes.size.toLong(), apkSha))
    }

    @Test
    fun `erreur HTTP 404 - fichier supprime`() {
        server.enqueue(MockResponse.Builder().code(404).build())
        val dest = File(tmp, "update.apk")
        val e = assertThrows<UpdateDownloadException> { downloader.download(release(), dest) }
        assertEquals(Kind.HTTP, e.kind)
        assertEquals(404, e.httpCode)
        assertFalse(dest.exists())
    }

    @Test
    fun `verification SHA-256 d un fichier`() {
        val f = File(tmp, "f.apk").apply { writeBytes(apkBytes) }
        assertTrue(FileIntegrity.matches(f, apkBytes.size.toLong(), apkSha))
        assertFalse(FileIntegrity.matches(f, apkBytes.size.toLong() - 1, apkSha))
        assertFalse(FileIntegrity.matches(f, apkBytes.size.toLong(), sha256Hex(byteArrayOf(1))))
        assertFalse(FileIntegrity.matches(f, apkBytes.size.toLong(), apkSha.uppercase()))
        assertFalse(FileIntegrity.matches(f, apkBytes.size.toLong(), "zz"))
        assertFalse(FileIntegrity.matches(File(tmp, "absent.apk"), apkBytes.size.toLong(), apkSha))
        // Un seul octet modifie suffit a refuser.
        f.writeBytes(apkBytes.copyOf().also { it[100] = (it[100] + 1).toByte() })
        assertFalse(FileIntegrity.matches(f, apkBytes.size.toLong(), apkSha))
        assertTrue(FileIntegrity.digestMatches(MessageDigest.getInstance("SHA-256").digest(apkBytes), apkSha))
    }

    @Test
    fun `update json - HTTPS impose, taille 64 Ko, requete conditionnelle`() {
        val c = UpdateManifestClient("http://example.org/")
        assertFalse(c.isConfigured)
        assertEquals(Kind.NOT_HTTPS, assertThrows<UpdateDownloadException> { c.download() }.kind)

        val local = UpdateManifestClient(server.url("/site").toString(), httpCacheDir = File(tmp, "http"), allowCleartextForTests = true)
        server.enqueue(MockResponse.Builder().body("{}").addHeader("ETag", "\"u1\"").build())
        server.enqueue(MockResponse.Builder().body("c2ln").addHeader("ETag", "\"s1\"").build())
        val d = local.download()
        assertEquals("{}", String(d.json))
        assertEquals("/site/update.json", server.takeRequest().url.encodedPath)
        assertEquals("/site/update.json.sig", server.takeRequest().url.encodedPath)

        server.enqueue(MockResponse.Builder().code(304).addHeader("ETag", "\"u1\"").build())
        server.enqueue(MockResponse.Builder().code(304).addHeader("ETag", "\"s1\"").build())
        assertEquals("c2ln", String(local.download().signature))
        assertEquals("\"u1\"", server.takeRequest().headers["If-None-Match"])
        server.takeRequest()

        server.enqueue(MockResponse.Builder().body("x".repeat(64 * 1024 + 1)).build())
        assertEquals(Kind.TOO_LARGE, assertThrows<UpdateDownloadException> { local.download() }.kind)

        server.enqueue(MockResponse.Builder().code(404).build())
        val e = assertThrows<UpdateDownloadException> { local.download() }
        assertEquals(Kind.HTTP, e.kind)
        assertEquals(404, e.httpCode)
        local.close()
    }

    @Test
    fun `hotes autorises par defaut pour les releases GitHub`() {
        assertTrue(ApkDownloader.isGitHubReleaseHost("github.com"))
        assertTrue(ApkDownloader.isGitHubReleaseHost("objects.githubusercontent.com"))
        assertTrue(ApkDownloader.isGitHubReleaseHost("release-assets.githubusercontent.com"))
        assertFalse(ApkDownloader.isGitHubReleaseHost("githubusercontent.com.evil.example"))
        assertFalse(ApkDownloader.isGitHubReleaseHost("evilgithubusercontent.com"))
        assertFalse(ApkDownloader.isGitHubReleaseHost("evil.example"))
        assertEquals("github.com", "https://github.com/x".toHttpUrl().host)
    }

    private fun sha256Hex(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
