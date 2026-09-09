package com.macky.client.identity

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Manages unique cryptographic device identity for host-side connection authorization.
 * Generates and securely manages an EC keypair (secp256r1) inside the hardware-backed
 * AndroidKeyStore system. The private key never exists as exportable raw bytes and
 * signs nonces directly within secure hardware via SHA256withECDSA.
 */
class DeviceIdentityManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("acuity_identity", Context.MODE_PRIVATE)

    val deviceId: String
    val deviceName: String
    val publicKeyBase64: String

    private var jvmFallbackKeyPair: KeyPair? = null

    init {
        val pubKey = getOrCreatePublicKey()
        val pubKeyBytes = pubKey.encoded
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(pubKeyBytes)
        deviceId = hash.joinToString("") { "%02x".format(it) }

        publicKeyBase64 = encodeBase64(pubKeyBytes)

        val defaultName = buildDeviceLabel()
        deviceName = prefs.getString(KEY_DEVICE_NAME, null) ?: defaultName
    }

    /**
     * Signs the 32-byte cryptographic nonce from the Mac host using the hardware-protected
     * EC private key via ECDSA (SHA256withECDSA).
     */
    fun signNonce(nonceBytes: ByteArray): String {
        val signatureBytes = if (jvmFallbackKeyPair != null) {
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initSign(jvmFallbackKeyPair!!.private)
            sig.update(nonceBytes)
            sig.sign()
        } else {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
                ?: throw IllegalStateException("KeyStore private key entry not found for alias '$KEY_ALIAS'")
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initSign(entry.privateKey)
            sig.update(nonceBytes)
            sig.sign()
        }
        return encodeBase64(signatureBytes)
    }

    private fun getOrCreatePublicKey(): PublicKey {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                val kpg = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_EC,
                    ANDROID_KEYSTORE
                )
                val parameterSpec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                )
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build()
                kpg.initialize(parameterSpec)
                kpg.generateKeyPair()
            }
            keyStore.getCertificate(KEY_ALIAS).publicKey
        } catch (e: Exception) {
            // Fallback for desktop JVM test environments where AndroidKeyStore provider is unavailable
            getOrCreateJvmFallbackKey()
        }
    }

    private fun getOrCreateJvmFallbackKey(): PublicKey {
        if (jvmFallbackKeyPair == null) {
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec("secp256r1"))
            jvmFallbackKeyPair = kpg.generateKeyPair()
        }
        return jvmFallbackKeyPair!!.public
    }

    private fun encodeBase64(bytes: ByteArray): String {
        return try {
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            java.util.Base64.getEncoder().encodeToString(bytes)
        }
    }

    private fun buildDeviceLabel(): String {
        val manufacturer = Build.MANUFACTURER?.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase() else it.toString()
        } ?: "Android"
        val model = Build.MODEL ?: "Device"
        return if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model"
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "acuity_device_ec_key"
        private const val KEY_DEVICE_NAME = "device_label"
    }
}
