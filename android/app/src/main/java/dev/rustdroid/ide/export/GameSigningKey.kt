package dev.rustdroid.ide.export

import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * R4A: the key that signs the user's exported games. Created once and kept
 * in R4A's private storage, so every export of a game is signed the same way
 * and installs as an update of the previous one. (Android only lets an app
 * update itself with the same key — losing it means a new app.)
 *
 * The self-signed certificate is built by hand (a few DER structures):
 * Android has no public API to create one, and this avoids a crypto library.
 */
object GameSigningKey {

    class Key(val privateKey: PrivateKey, val certificate: X509Certificate)

    private const val KEY_FILE = "r4a-games.pk8"
    private const val CERT_FILE = "r4a-games.der"

    fun loadOrCreate(dir: File, owner: String = "R4A"): Key {
        val keyFile = File(dir, KEY_FILE)
        val certFile = File(dir, CERT_FILE)
        if (keyFile.isFile && certFile.isFile) {
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(certFile.inputStream()) as X509Certificate
            return Key(key, cert)
        }
        val created = create(owner)
        dir.mkdirs()
        certFile.writeBytes(created.certificate.encoded)
        keyFile.writeBytes(created.privateKey.encoded)
        return created
    }

    fun create(owner: String): Key {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048, SecureRandom())
        val pair = gen.generateKeyPair()

        val sha256WithRsa = seq(oid("1.2.840.113549.1.1.11"), byteArrayOf(0x05, 0x00))
        val name = seq(set(seq(oid("2.5.4.3"), tlv(0x0c, owner.toByteArray(Charsets.UTF_8)))))
        val now = System.currentTimeMillis()
        val validity = seq(
            time(Date(now - 24L * 3600 * 1000)),
            time(Date(now + 30L * 365 * 24 * 3600 * 1000)), // 30 years
        )
        val serial = BigInteger(64, SecureRandom()).abs().add(BigInteger.ONE)
        val tbs = seq(
            tlv(0xa0, tlv(0x02, byteArrayOf(2))), // version v3
            tlv(0x02, serial.toByteArray()),
            sha256WithRsa,
            name,
            validity,
            name,
            pair.public.encoded, // SubjectPublicKeyInfo, already DER
        )
        val sig = Signature.getInstance("SHA256withRSA").run {
            initSign(pair.private)
            update(tbs)
            sign()
        }
        val certDer = seq(tbs, sha256WithRsa, tlv(0x03, byteArrayOf(0) + sig))
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(certDer.inputStream()) as X509Certificate
        return Key(pair.private, cert)
    }

    // ---- tiny DER writer ----

    private fun tlv(tag: Int, value: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val len = value.size
        when {
            len < 0x80 -> out.write(len)
            len < 0x100 -> { out.write(0x81); out.write(len) }
            len < 0x10000 -> { out.write(0x82); out.write(len shr 8); out.write(len and 0xff) }
            else -> { out.write(0x83); out.write(len shr 16); out.write((len shr 8) and 0xff); out.write(len and 0xff) }
        }
        out.write(value)
        return out.toByteArray()
    }

    private fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })
    private fun set(vararg parts: ByteArray) = tlv(0x31, parts.fold(ByteArray(0)) { a, b -> a + b })

    private fun oid(dotted: String): ByteArray {
        val p = dotted.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((p[0] * 40 + p[1]).toInt())
        for (v in p.drop(2)) {
            val bytes = ArrayList<Int>()
            var x = v
            bytes += (x and 0x7f).toInt()
            x = x shr 7
            while (x > 0) { bytes += ((x and 0x7f) or 0x80).toInt(); x = x shr 7 }
            bytes.reversed().forEach { out.write(it) }
        }
        return tlv(0x06, out.toByteArray())
    }

    /** UTCTime until 2049, GeneralizedTime after (X.509 rule). */
    private fun time(d: Date): ByteArray {
        val year = SimpleDateFormat("yyyy", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(d).toInt()
        val (tag, pattern) = if (year < 2050) 0x17 to "yyMMddHHmmss'Z'" else 0x18 to "yyyyMMddHHmmss'Z'"
        val text = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(d)
        return tlv(tag, text.toByteArray(Charsets.US_ASCII))
    }
}
