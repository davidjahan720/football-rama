package fr.footballrama.app.pairing

import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Serveur HTTP ephemere du jumelage (implementation minimale auditee : ServerSocket + [HttpRequestReader]).
 *
 * - ecoute sur UNE adresse (l'IPv4 privee du reseau local, jamais 0.0.0.0) ; premier port libre de [Config.ports],
 *   sinon port ephemere ;
 * - [Config.maxConnections] connexions simultanees au plus (au-dela : fermeture immediate) ;
 * - date limite de lecture GLOBALE par connexion ([Config.readTimeoutMs]) : un client lent (slowloris) est coupe ;
 * - pairs hors reseau prive refuses ;
 * - arret automatique apres [Config.lifetimeMs], apres un succes ou une session invalidee, ou sur [stop].
 */
class PairingServer(
    private val address: InetAddress,
    val session: PairingSession,
    private val submitter: CredentialSubmitter,
    private val listener: PairingListener,
    private val config: Config = Config(),
) {
    data class Config(
        val ports: IntRange = 8090..8099,
        val lifetimeMs: Long = 5 * 60_000L,
        val readTimeoutMs: Long = 10_000L,
        val maxConnections: Int = 4,
        val limits: HttpLimits = HttpLimits(),
        /** Delai laisse aux reponses en cours a l'arret (ex. page "Connexion reussie"). */
        val stopGraceMs: Long = 3_000L,
    )

    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val permits = Semaphore(config.maxConnections)
    private val clients: MutableSet<Socket> = Collections.synchronizedSet(HashSet())
    private lateinit var serverSocket: ServerSocket
    private lateinit var executor: ExecutorService
    private lateinit var handler: PairingHandler

    /** Port effectivement utilise (apres [start]). */
    var port: Int = -1
        private set

    /** "ip:port", valeur exacte attendue dans l'en-tete Host. */
    val hostHeader: String get() = "${address.hostAddress}:$port"

    /** URL a encoder dans le QR code. */
    val url: String get() = "http://$hostHeader${handler.path}"

    val isRunning: Boolean get() = started.get() && !closed.get()

    /** Demarre l'ecoute. Leve IOException si aucun port n'est disponible. A appeler hors thread UI. */
    @Throws(IOException::class)
    fun start(): Int {
        check(started.compareAndSet(false, true)) { "already started" }
        serverSocket = bind()
        port = serverSocket.localPort
        handler = PairingHandler(session, hostHeader, submitter, listener)
        val counter = AtomicInteger()
        executor = Executors.newFixedThreadPool(config.maxConnections) { r ->
            Thread(r, "pairing-http-${counter.incrementAndGet()}").apply { isDaemon = true }
        }
        Thread(::acceptLoop, "pairing-accept").apply { isDaemon = true }.start()
        return port
    }

    private fun bind(): ServerSocket {
        for (p in config.ports) {
            val s = ServerSocket()
            try {
                s.reuseAddress = false
                s.bind(InetSocketAddress(address, p), BACKLOG)
                return s
            } catch (_: IOException) {
                closeQuietly(s)
            }
        }
        // Plage occupee : port ephemere choisi par le systeme.
        return ServerSocket().apply { bind(InetSocketAddress(address, 0), BACKLOG) }
    }

    private fun acceptLoop() {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.lifetimeMs)
        while (!closed.get()) {
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (remainingMs <= 0) {
                if (!closed.get()) {
                    stop()
                    listener.onEvent(PairingEvent.Expired)
                }
                return
            }
            val socket = try {
                serverSocket.soTimeout = remainingMs.coerceIn(1, ACCEPT_TICK_MS).toInt()
                serverSocket.accept()
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: IOException) {
                return // socket ferme par stop()
            }
            if (closed.get() || !isAllowedPeer(socket.inetAddress) || !permits.tryAcquire()) {
                closeQuietly(socket)
                continue
            }
            clients.add(socket)
            try {
                executor.execute {
                    try {
                        serve(socket)
                    } finally {
                        clients.remove(socket)
                        closeQuietly(socket)
                        permits.release()
                    }
                }
            } catch (_: Exception) { // executeur arrete entre-temps
                clients.remove(socket)
                closeQuietly(socket)
                permits.release()
            }
        }
    }

    private fun serve(socket: Socket) {
        val readDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.readTimeoutMs)
        val response = try {
            socket.tcpNoDelay = true
            socket.soTimeout = config.readTimeoutMs.toInt()
            val request = HttpRequestReader.read(socket.getInputStream(), config.limits) {
                val left = TimeUnit.NANOSECONDS.toMillis(readDeadline - System.nanoTime())
                if (left <= 0) throw SocketTimeoutException()
                socket.soTimeout = left.toInt()
            }
            handler.handle(request)
        } catch (e: HttpException) {
            HttpResponse.empty(e.status)
        } catch (_: SocketTimeoutException) {
            HttpResponse.empty(408)
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            HttpResponse.empty(500)
        }
        if (response != null) {
            try {
                response.writeTo(socket.getOutputStream())
                socket.shutdownOutput()
                lingerDrain(socket)
            } catch (_: IOException) {
            }
        }
        if (session.isFinished) stop()
    }

    /**
     * Fermeture "en douceur" : lit et jette (borne en taille et en temps) ce que le client envoie encore, pour que
     * la reponse (413, 431...) ne soit pas detruite par un RST si des octets non lus restent dans le tampon.
     */
    private fun lingerDrain(socket: Socket) {
        socket.soTimeout = LINGER_MS
        val sink = ByteArray(4096)
        var total = 0
        val input = socket.getInputStream()
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LINGER_MS.toLong())
        try {
            while (total < LINGER_MAX_BYTES && System.nanoTime() < end) {
                val n = input.read(sink)
                if (n < 0) break
                total += n
            }
        } catch (_: IOException) {
        }
    }

    /** Arret idempotent, non bloquant : plus aucune connexion acceptee ; les reponses en cours ont [Config.stopGraceMs]. */
    fun stop() {
        if (!closed.compareAndSet(false, true)) return
        session.close()
        if (!started.get()) return
        closeQuietly(serverSocket)
        executor.shutdown()
        Thread({
            try {
                if (!executor.awaitTermination(config.stopGraceMs, TimeUnit.MILLISECONDS)) {
                    synchronized(clients) { clients.toList() }.forEach(::closeQuietly)
                    executor.shutdownNow()
                }
            } catch (_: InterruptedException) {
                executor.shutdownNow()
            }
        }, "pairing-stop").apply { isDaemon = true }.start()
    }

    companion object {
        private const val BACKLOG = 8
        private const val ACCEPT_TICK_MS = 1_000L
        private const val LINGER_MS = 500
        private const val LINGER_MAX_BYTES = 64 * 1024

        /** Pairs autorises : reseau local prive (RFC 1918), lien local ou boucle locale (tests). */
        fun isAllowedPeer(a: InetAddress?): Boolean =
            a != null && (a.isLoopbackAddress || a.isLinkLocalAddress || (a is Inet4Address && LanAddress.isPrivateIpv4(a)))
    }
}
