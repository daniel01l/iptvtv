package pl.iptv.shared

import android.util.Base64
import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Aktywacja wersji zabezpieczonej (wspólne dla odtwarzacza i aplikacji administratora).
 * Lista urządzeń jest podpisana kluczem prywatnym administratora (ECDSA P-256); odtwarzacz ma tylko klucz publiczny,
 * więc nie da się podrobić aktywacji ani jej wyciągnąć z aplikacji.
 */
object ActivationFormat {
    const val ACTIVE = "a"
    const val BLOCKED = "b"

    /** Alfabet bez mylących znaków (0/O, 1/I/L). */
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    private fun sha256(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())

    /** Kod urządzenia „XXXX-XXXX” z identyfikatora urządzenia (identyfikator nie jest wysyłany ani możliwy do odtworzenia). */
    fun codeFromId(deviceId: String): String {
        val h = sha256("dplayer|$deviceId")
        val sb = StringBuilder()
        for (i in 0 until 8) sb.append(ALPHABET[(h[i].toInt() and 0xFF) % ALPHABET.length])
        return sb.substring(0, 4) + "-" + sb.substring(4)
    }

    fun normalize(code: String): String {
        val c = code.uppercase().filter { it.isLetterOrDigit() }
        return if (c.length == 8) c.substring(0, 4) + "-" + c.substring(4) else c
    }

    /** [status] – wersja testowa (dplayer), [full] – wersja pełna (iptvtv); każda blokowana osobno. */
    /** [admin] – kod administratora zaszyfrowany dla tego urządzenia („moje urządzenie”: raporty bez wpisywania kodu). */
    data class Entry(val status: String, val note: String = "", val changed: Long = 0, val full: String = "", val admin: String = "") {
        fun forEdition(fullEdition: Boolean) = if (fullEdition) full else status
    }

    /** Treść listy (podpisywana jako tekst – bez problemów z kolejnością pól). */
    fun payload(entries: Map<String, Entry>, ts: Long): String {
        val d = JSONObject()
        entries.toSortedMap().forEach { (k, e) -> d.put(k, JSONObject().put("s", e.status).put("n", e.note).put("t", e.changed).apply { if (e.full.isNotEmpty()) put("f", e.full); if (e.admin.isNotEmpty()) put("k", e.admin) }) }
        return JSONObject().put("v", 1).put("ts", ts).put("devices", d).toString()
    }

    fun entries(payload: String): Map<String, Entry> {
        val d = JSONObject(payload).optJSONObject("devices") ?: return emptyMap()
        return d.keys().asSequence().associateWith { k -> d.getJSONObject(k).let { Entry(it.optString("s"), it.optString("n"), it.optLong("t"), it.optString("f"), it.optString("k")) } }
    }

    fun timestamp(payload: String): Long = JSONObject(payload).optLong("ts")

    /** Plik w repozytorium: {"payload": "...", "sig": "..."}. */
    fun wrap(payload: String, sig: String): String = JSONObject().put("payload", payload).put("sig", sig).toString(2)

    fun unwrap(file: String): Pair<String, String> = JSONObject(file).let { it.getString("payload") to it.getString("sig") }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s.trim(), Base64.DEFAULT)

    /** Nowa para kluczy: (prywatny PKCS#8, publiczny X.509) w Base64. */
    fun newKeyPair(): Pair<String, String> {
        val g = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val kp = g.generateKeyPair()
        return b64(kp.private.encoded) to b64(kp.public.encoded)
    }

    fun publicFromPrivateCheck(priv: String, pub: String): Boolean = runCatching {
        val p = sign("test", priv); verify("test", p, pub)
    }.getOrDefault(false)

    fun sign(payload: String, privB64: String): String {
        val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(unb64(privB64)))
        return b64(Signature.getInstance("SHA256withECDSA").apply { initSign(key); update(payload.toByteArray()) }.sign())
    }

    fun verify(payload: String, sigB64: String, pubB64: String): Boolean = runCatching {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(unb64(pubB64)))
        Signature.getInstance("SHA256withECDSA").apply { initVerify(key); update(payload.toByteArray()) }.verify(unb64(sigB64))
    }.getOrDefault(false)

    /** Wspólny klucz administratora i urządzenia (ECDH) – tylko to urządzenie odczyta przekazane mu dane. */
    fun sharedKey(privB64: String, otherPubB64: String): ByteArray {
        val kf = KeyFactory.getInstance("EC")
        val ka = javax.crypto.KeyAgreement.getInstance("ECDH")
        ka.init(kf.generatePrivate(PKCS8EncodedKeySpec(unb64(privB64))))
        ka.doPhase(kf.generatePublic(X509EncodedKeySpec(unb64(otherPubB64))), true)
        return MessageDigest.getInstance("SHA-256").digest(ka.generateSecret())
    }

    fun seal(plain: String, key: ByteArray): String {
        val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.GCMParameterSpec(128, iv))
        return b64(iv + c.doFinal(plain.toByteArray()))
    }

    fun open(sealed: String, key: ByteArray): String {
        val d = unb64(sealed)
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.GCMParameterSpec(128, d, 0, 12))
        return String(c.doFinal(d, 12, d.size - 12))
    }

    /** Skrzynka przekaźnika na zgłoszenia urządzeń (kod, wersja, model, czas) – wyznaczona z klucza publicznego. */
    fun checkinTopic(pubB64: String): String = "dplayer_" + sha256("checkin|" + pubB64.trim()).take(12).joinToString("") { "%02x".format(it) }

    /** Krótki odcisk klucza do porównania (Admin ↔ aplikacja). */
    fun fingerprint(pubB64: String): String = sha256(pubB64.trim()).take(6).joinToString(":") { "%02X".format(it) }
}
