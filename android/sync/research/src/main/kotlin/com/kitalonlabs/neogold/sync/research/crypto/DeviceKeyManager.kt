package com.kitalonlabs.neogold.sync.research.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.kitalonlabs.neogold.sync.research.api.models.Jwk
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.ConcurrentHashMap

data class DeviceKeyResult(
    val publicJwk: Jwk,
    val thumbprint: String,
    val attestationChain: List<String>?
)

interface DeviceKeyManager {
    fun generateDeviceKey(alias: String, enrollmentCode: String): DeviceKeyResult
    fun signPayload(alias: String, payload: ByteArray): ByteArray
    fun deleteDeviceKey(alias: String)
    fun hasDeviceKey(alias: String): Boolean
    fun getPublicJwk(alias: String): Jwk?
    fun getThumbprint(alias: String): String?
    fun getAttestationChain(alias: String): List<String>?
}

class AndroidDeviceKeyManager(
    private val keyStoreType: String = "AndroidKeyStore"
) : DeviceKeyManager {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(keyStoreType).apply { load(null) }
    }

    override fun generateDeviceKey(alias: String, enrollmentCode: String): DeviceKeyResult {
        val challenge = Base64Url.sha256(enrollmentCode.toByteArray(Charsets.UTF_8))
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, keyStoreType)

        var isStrongBox = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val strongBoxSpec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setAttestationChallenge(challenge)
                    .setIsStrongBoxBacked(true)
                    .build()
                kpg.initialize(strongBoxSpec)
                kpg.generateKeyPair()
                isStrongBox = true
            } catch (_: Exception) {
                // Fall back to TEE below
            }
        }

        if (!isStrongBox) {
            val teeSpec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setAttestationChallenge(challenge)
                .build()
            kpg.initialize(teeSpec)
            kpg.generateKeyPair()
        }

        val jwk = getPublicJwk(alias) ?: error("Failed to retrieve generated public key")
        val thumbprint = computeThumbprint(jwk)
        val chain = getAttestationChain(alias)

        return DeviceKeyResult(
            publicJwk = jwk,
            thumbprint = thumbprint,
            attestationChain = chain
        )
    }

    override fun signPayload(alias: String, payload: ByteArray): ByteArray {
        val privateKey = keyStore.getKey(alias, null) as? PrivateKey
            ?: error("Device private key not found for alias: $alias")
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(payload)
        val derBytes = signature.sign()
        return JoseSignatureConverter.derToJose(derBytes)
    }

    override fun deleteDeviceKey(alias: String) {
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
        }
    }

    override fun hasDeviceKey(alias: String): Boolean {
        return keyStore.containsAlias(alias)
    }

    override fun getPublicJwk(alias: String): Jwk? {
        val cert = keyStore.getCertificate(alias) ?: return null
        val pubKey = cert.publicKey as? ECPublicKey ?: return null
        return ecPublicKeyToJwk(pubKey)
    }

    override fun getThumbprint(alias: String): String? {
        val jwk = getPublicJwk(alias) ?: return null
        return computeThumbprint(jwk)
    }

    override fun getAttestationChain(alias: String): List<String>? {
        val chain = keyStore.getCertificateChain(alias) ?: return null
        if (chain.isEmpty()) return null
        return chain.map { Base64Url.encodeStandard(it.encoded) }
    }

    companion object {
        fun ecPublicKeyToJwk(pubKey: ECPublicKey): Jwk {
            val xBytes = pubKey.w.affineX.toByteArray()
            val yBytes = pubKey.w.affineY.toByteArray()
            val x32 = toFixed32Bytes(xBytes)
            val y32 = toFixed32Bytes(yBytes)
            return Jwk(
                kty = "EC",
                crv = "P-256",
                x = Base64Url.encode(x32),
                y = Base64Url.encode(y32)
            )
        }

        fun computeThumbprint(jwk: Jwk): String {
            val canonicalJson = "{\"crv\":\"${jwk.crv}\",\"kty\":\"${jwk.kty}\",\"x\":\"${jwk.x}\",\"y\":\"${jwk.y}\"}"
            val hash = Base64Url.sha256(canonicalJson.toByteArray(Charsets.UTF_8))
            return Base64Url.encode(hash)
        }

        private fun toFixed32Bytes(src: ByteArray): ByteArray {
            val nonZeroIndex = src.indexOfFirst { it != 0.toByte() }.let { if (it == -1) src.size else it }
            val effectiveLen = src.size - nonZeroIndex
            val dest = ByteArray(32)
            if (effectiveLen > 32) {
                System.arraycopy(src, src.size - 32, dest, 0, 32)
            } else {
                val pad = 32 - effectiveLen
                System.arraycopy(src, nonZeroIndex, dest, pad, effectiveLen)
            }
            return dest
        }
    }
}

/**
 * In-memory software device key manager for unit tests and JVM environments where AndroidKeyStore is unavailable.
 */
class SoftwareDeviceKeyManager : DeviceKeyManager {
    private val keyPairs = ConcurrentHashMap<String, KeyPair>()
    private val attestationChains = ConcurrentHashMap<String, List<String>>()

    override fun generateDeviceKey(alias: String, enrollmentCode: String): DeviceKeyResult {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = kpg.generateKeyPair()
        keyPairs[alias] = keyPair

        val jwk = AndroidDeviceKeyManager.ecPublicKeyToJwk(keyPair.public as ECPublicKey)
        val thumbprint = AndroidDeviceKeyManager.computeThumbprint(jwk)

        return DeviceKeyResult(
            publicJwk = jwk,
            thumbprint = thumbprint,
            attestationChain = attestationChains[alias]
        )
    }

    fun setAttestationChain(alias: String, chain: List<String>) {
        attestationChains[alias] = chain
    }

    override fun signPayload(alias: String, payload: ByteArray): ByteArray {
        val keyPair = keyPairs[alias] ?: error("No key pair found for alias $alias")
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(keyPair.private)
        sig.update(payload)
        return JoseSignatureConverter.derToJose(sig.sign())
    }

    override fun deleteDeviceKey(alias: String) {
        keyPairs.remove(alias)
        attestationChains.remove(alias)
    }

    override fun hasDeviceKey(alias: String): Boolean = keyPairs.containsKey(alias)

    override fun getPublicJwk(alias: String): Jwk? {
        val kp = keyPairs[alias] ?: return null
        return AndroidDeviceKeyManager.ecPublicKeyToJwk(kp.public as ECPublicKey)
    }

    override fun getThumbprint(alias: String): String? {
        val jwk = getPublicJwk(alias) ?: return null
        return AndroidDeviceKeyManager.computeThumbprint(jwk)
    }

    override fun getAttestationChain(alias: String): List<String>? = attestationChains[alias]
}
