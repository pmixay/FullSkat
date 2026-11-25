package com.fullskat.phone.watchinstall

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/**
 * This phone's ADB identity: an RSA key and a self-signed certificate for it. The watch remembers the key when it is
 * paired, so it is made once and kept in no-backup app storage (PKCS#8 and DER); a restored or new phone pairs again.
 */
class AdbKeys private constructor(val privateKey: PrivateKey, val certificate: X509Certificate) {
    companion object {
        private const val KEY_FILE = "adbkey.pk8"
        private const val CERT_FILE = "adbkey.der"

        /** Loads the identity from [dir], making (and saving) a new one when there is none or it is unreadable. */
        @Synchronized
        fun load(dir: File): AdbKeys {
            val keyFile = File(dir, KEY_FILE)
            val certFile = File(dir, CERT_FILE)
            if (keyFile.exists() && certFile.exists()) {
                runCatching {
                    val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                    val cert = certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }
                    return AdbKeys(key, cert)
                }
            }
            val generated = generate()
            dir.mkdirs()
            writeAtomically(keyFile, generated.privateKey.encoded)
            writeAtomically(certFile, generated.certificate.encoded)
            return generated
        }

        private fun generate(): AdbKeys {
            val random = SecureRandom()
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, random) }.generateKeyPair()
            val name = X500Name("CN=FullSkat")
            val now = System.currentTimeMillis()
            val day = 86_400_000L
            val builder = JcaX509v3CertificateBuilder(
                name,
                BigInteger(63, random).add(BigInteger.ONE),
                Date(now - day), // a watch whose clock is a little behind still takes it
                Date(now + 30 * 365 * day),
                name,
                pair.public,
            )
            val signer = JcaContentSignerBuilder("SHA256withRSA").build(pair.private)
            val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
            return AdbKeys(pair.private, cert)
        }

        private fun writeAtomically(file: File, bytes: ByteArray) {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) {
                file.delete()
                check(tmp.renameTo(file)) { "Cannot save $file" }
            }
        }
    }
}
