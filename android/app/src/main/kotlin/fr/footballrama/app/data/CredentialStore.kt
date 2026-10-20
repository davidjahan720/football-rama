package fr.footballrama.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import fr.footballrama.core.xtream.XtreamAccount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Identifiants Xtream (URL serveur + utilisateur + mot de passe) chiffres au repos.
 *
 * Choix : AES-256-GCM avec une cle generee DANS l'Android Keystore (non exportable, materielle si le SoC le
 * permet), texte chiffre stocke dans DataStore Preferences. Pas de Jetpack Security (EncryptedSharedPreferences
 * est deprecie) ni de Tink AndroidKeysetManager (qui retombe sur un keyset EN CLAIR si le Keystore est
 * indisponible) : ici, sans Keystore fonctionnel, rien n'est enregistre ([CredentialStoreException]).
 * Donnees associees (AAD) = version du format, pour empecher toute substitution.
 * allowBackup=false + dataExtractionRules : rien ne quitte l'appareil.
 */
class CredentialStore(context: Context) {

    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { context.applicationContext.preferencesDataStoreFile(FILE) },
    )

    /** Vrai si des identifiants sont enregistres. */
    val hasAccount: Flow<Boolean> = dataStore.data.catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { it[KEY_BLOB] != null }

    /** Vrai si le serveur enregistre est en HTTP (l'UI doit avertir que le trafic circule en clair). */
    val isCleartext: Flow<Boolean> = dataStore.data.catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { it[KEY_CLEARTEXT] ?: false }

    /**
     * Valide puis enregistre les identifiants.
     * @throws IllegalArgumentException URL/identifiants invalides
     * @throws CredentialStoreException Keystore indisponible
     */
    suspend fun save(serverUrl: String, username: String, password: String): XtreamAccount {
        val account = XtreamAccount.create(serverUrl, username, password)
            ?: throw IllegalArgumentException("invalid account")
        val blob = encrypt(serialize(account))
        dataStore.edit {
            it[KEY_BLOB] = blob
            it[KEY_CLEARTEXT] = account.isCleartext
        }
        return account
    }

    /** null si aucun identifiant ou si le dechiffrement echoue (cle invalidee : les donnees sont alors effacees). */
    suspend fun load(): XtreamAccount? {
        val blob = runCatching { dataStore.data.first()[KEY_BLOB] }.getOrNull() ?: return null
        val plain = try {
            decrypt(blob)
        } catch (e: Exception) {
            // Donnees definitivement illisibles (cle detruite/invalidee, blob altere) : on efface.
            // Erreur transitoire du Keystore : on garde les donnees pour la prochaine tentative.
            if (e is javax.crypto.AEADBadTagException || e is IllegalArgumentException ||
                e is android.security.keystore.KeyPermanentlyInvalidatedException || e is MissingKeyException
            ) {
                clear()
            }
            return null
        }
        return deserialize(plain)
    }

    /** "Se deconnecter" : efface le texte chiffre ET detruit la cle du Keystore. */
    suspend fun clear() {
        runCatching { dataStore.edit { it.clear() } }
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
    }

    // ------------------------------------------------------------------ chiffrement

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun secretKey(): SecretKey {
        try {
            (keyStore().getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            return generator.generateKey()
        } catch (e: GeneralSecurityException) {
            throw CredentialStoreException()
        } catch (e: java.io.IOException) {
            throw CredentialStoreException()
        }
    }

    private fun encrypt(plain: ByteArray): String {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey()) // IV aleatoire genere par le Keystore
            cipher.updateAAD(AAD)
            val ct = cipher.doFinal(plain)
            val iv = cipher.iv
            val out = ByteArray(2 + iv.size + ct.size)
            out[0] = FORMAT_VERSION
            out[1] = iv.size.toByte()
            iv.copyInto(out, 2)
            ct.copyInto(out, 2 + iv.size)
            return Base64.encodeToString(out, Base64.NO_WRAP)
        } catch (e: GeneralSecurityException) {
            throw CredentialStoreException()
        } finally {
            plain.fill(0)
        }
    }

    private fun decrypt(blob: String): ByteArray {
        val data = Base64.decode(blob, Base64.NO_WRAP)
        require(data.size > 2 && data[0] == FORMAT_VERSION)
        val ivLen = data[1].toInt()
        require(ivLen in 12..16 && data.size > 2 + ivLen)
        val key = keyStore().getKey(KEY_ALIAS, null) as? SecretKey ?: throw MissingKeyException()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 2, ivLen))
        cipher.updateAAD(AAD)
        return cipher.doFinal(data, 2 + ivLen, data.size - 2 - ivLen)
    }

    private fun serialize(a: XtreamAccount): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use {
            it.writeUTF(a.serverUrl.toString())
            it.writeUTF(a.username)
            it.writeUTF(a.password)
        }
        return bytes.toByteArray()
    }

    private fun deserialize(plain: ByteArray): XtreamAccount? = try {
        DataInputStream(ByteArrayInputStream(plain)).use { XtreamAccount.create(it.readUTF(), it.readUTF(), it.readUTF()) }
    } catch (_: Exception) {
        null
    } finally {
        plain.fill(0)
    }

    private companion object {
        const val FILE = "xtream_credentials"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "football_rama_credentials_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT_VERSION: Byte = 1
        val AAD = "football_rama/credentials/v1".toByteArray()
        val KEY_BLOB = stringPreferencesKey("blob")
        val KEY_CLEARTEXT = booleanPreferencesKey("cleartext")
    }
}

private class MissingKeyException : GeneralSecurityException()

/** Keystore Android indisponible : les identifiants ne peuvent pas etre enregistres de facon sure. */
class CredentialStoreException : Exception("secure storage unavailable")
