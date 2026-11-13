package pl.iptv.shared

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Szyfrowanie wiadomości przekaźnika – wspólne dla IptvTv i IptvTv Admin. */
object RelayCrypto {
    const val DEFAULT_SERVER = "https://ntfy.sh"
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private const val MAGIC = "IPTV1"

    fun newCode(): String {
        val r = SecureRandom()
        return (1..12).map { ALPHABET[r.nextInt(ALPHABET.length)] }.joinToString("").chunked(4).joinToString("-")
    }

    fun normalize(code: String) = code.uppercase().filter { it.isLetterOrDigit() }

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Skrzynka urządzenia (listy, aktualizacje, polecenia). */
    fun topic(code: String) = "iptvtv-" + sha("topic:" + normalize(code)).take(32)

    /** Skrzynka administratora (raporty urządzeń). */
    fun adminTopic(code: String) = "iptvtv-adm-" + sha("admin:" + normalize(code)).take(32)

    private fun key(code: String): SecretKeySpec {
        val spec = PBEKeySpec(normalize(code).toCharArray(), "iptvtv-relay".toByteArray(), 20_000, 256)
        return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
    }

    private val keys = HashMap<String, SecretKeySpec>()
    private fun cachedKey(code: String) = synchronized(keys) { keys.getOrPut(normalize(code)) { key(code) } }

    fun encrypt(code: String, plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, cachedKey(code), GCMParameterSpec(128, iv)) }
        return MAGIC.toByteArray() + iv + c.doFinal(plain)
    }

    fun decrypt(code: String, blob: ByteArray): ByteArray {
        require(blob.size > 17 && String(blob, 0, 5) == MAGIC) { "Nieznany format" }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, cachedKey(code), GCMParameterSpec(128, blob, 5, 12)) }
        return c.doFinal(blob, 17, blob.size - 17)
    }
}
