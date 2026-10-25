package com.kitalonlabs.neogold.sync.research.crypto

import java.io.ByteArrayOutputStream

/**
 * Converts between ASN.1 DER ECDSA signatures and JOSE 64-byte (r || s) format (RFC 7518 Section 3.4).
 */
object JoseSignatureConverter {

    fun derToJose(derBytes: ByteArray): ByteArray {
        require(derBytes.isNotEmpty() && derBytes[0] == 0x30.toByte()) { "Invalid DER sequence tag" }

        var offset = 1
        val seqLen = derBytes[offset++].toInt() and 0xFF
        val actualSeqLen = if (seqLen and 0x80 != 0) {
            val numBytes = seqLen and 0x7F
            var len = 0
            for (i in 0 until numBytes) {
                len = (len shl 8) or (derBytes[offset++].toInt() and 0xFF)
            }
            len
        } else {
            seqLen
        }

        require(derBytes[offset++] == 0x02.toByte()) { "Expected DER INTEGER for R" }
        val rLen = derBytes[offset++].toInt() and 0xFF
        val rBytes = derBytes.copyOfRange(offset, offset + rLen)
        offset += rLen

        require(derBytes[offset++] == 0x02.toByte()) { "Expected DER INTEGER for S" }
        val sLen = derBytes[offset++].toInt() and 0xFF
        val sBytes = derBytes.copyOfRange(offset, offset + sLen)

        val jose = ByteArray(64)
        copyTo32Bytes(rBytes, jose, 0)
        copyTo32Bytes(sBytes, jose, 32)
        return jose
    }

    private fun copyTo32Bytes(src: ByteArray, dest: ByteArray, destOffset: Int) {
        val nonZeroIndex = src.indexOfFirst { it != 0.toByte() }.let { if (it == -1) src.size else it }
        val effectiveLen = src.size - nonZeroIndex
        if (effectiveLen > 32) {
            System.arraycopy(src, src.size - 32, dest, destOffset, 32)
        } else {
            val pad = 32 - effectiveLen
            System.arraycopy(src, nonZeroIndex, dest, destOffset + pad, effectiveLen)
        }
    }

    fun joseToDer(joseBytes: ByteArray): ByteArray {
        require(joseBytes.size == 64) { "JOSE signature must be exactly 64 bytes" }
        val r = joseBytes.copyOfRange(0, 32)
        val s = joseBytes.copyOfRange(32, 64)

        fun toDerInt(bytes: ByteArray): ByteArray {
            var start = 0
            while (start < bytes.size - 1 && bytes[start] == 0.toByte()) {
                start++
            }
            val needLeadingZero = (bytes[start].toInt() and 0x80) != 0
            val len = (bytes.size - start) + if (needLeadingZero) 1 else 0
            val out = ByteArray(len)
            if (needLeadingZero) {
                out[0] = 0x00
                System.arraycopy(bytes, start, out, 1, bytes.size - start)
            } else {
                System.arraycopy(bytes, start, out, 0, bytes.size - start)
            }
            return out
        }

        val rDer = toDerInt(r)
        val sDer = toDerInt(s)

        val totalLen = 2 + rDer.size + 2 + sDer.size
        val bos = ByteArrayOutputStream()
        bos.write(0x30)
        if (totalLen < 128) {
            bos.write(totalLen)
        } else {
            bos.write(0x81)
            bos.write(totalLen)
        }
        bos.write(0x02)
        bos.write(rDer.size)
        bos.write(rDer)
        bos.write(0x02)
        bos.write(sDer.size)
        bos.write(sDer)
        return bos.toByteArray()
    }
}
