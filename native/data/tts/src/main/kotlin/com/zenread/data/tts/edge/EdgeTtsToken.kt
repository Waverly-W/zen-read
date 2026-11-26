// Kotlin port of Modu lib/service/tts/edge_tts_token.dart, itself a Dart adaptation of
// edge-tts 7.3.0 src/edge_tts/drm.py.
// Copyright (c) 2025- rany <rany@riseup.net>
// Modifications Copyright (c) 2026 Modu contributors, Zen Read contributors.
// SPDX-License-Identifier: LGPL-3.0-only
// Source: https://github.com/rany2/edge-tts/blob/7.3.0/src/edge_tts/drm.py
// See LICENSES/edge-tts-LGPL-3.0.txt and LICENSE.
package com.zenread.data.tts.edge

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Consumer Read Aloud v2 authentication (the `Sec-MS-GEC` value). This is a protocol-specific
 * digest, not ordinary SHA-256 and not a general-purpose cryptographic primitive.
 *
 * All arithmetic works on 32-bit words held in [Long]s; Dart's 64-bit wrapping integer
 * semantics are reproduced by Kotlin's wrapping [Long] multiplication followed by masking.
 */
internal object EdgeTtsToken {
    fun v2Token(windowsTicks: Long, trustedClientToken: String): String {
        val data = "$windowsTicks$trustedClientToken".toByteArray(Charsets.US_ASCII).map { it.toInt() and 0xff }
        val digest = digest(data)
        val checksum = checksum(data, digest)
        return "${hex(checksum)}-${hex(digest)}"
    }

    private const val MASK = 0xffffffffL

    private val INITIAL = longArrayOf(
        0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A, 0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19,
    )
    private val STATE = longArrayOf(
        0xFD9FBEA2, 0x91243CE7, 0xBE499094, 0xCD2E1E0A, 0x6C006F49, 0x04BD35AB, 0xFEB0A7E4, 0x03EFFBD2,
    )
    private val SCHEDULE = longArrayOf(
        0xEB24BF9E, 0x1558691F, 0xA0C09EE2, 0x0CFA32BA, 0xA622EE8C, 0xD1DD9491, 0xC0DD25A0,
        0xF9E70AB6, 0xF0C31573, 0x7058CE4E, 0x7188E606, 0x00E5AB33, 0xD258B060, 0x72766564,
    )
    private val ROUND_CONSTANTS = longArrayOf(
        0x428A2F98, 0x71374491, 0xB5C0FBCF, 0xE9B5DBA5, 0x3956C25B, 0x59F111F1, 0x923F82A4, 0xAB1C5ED5,
        0xD807AA98, 0x12835B01, 0x243185BE, 0x550C7DC3, 0x72BE5D74, 0x80DEB1FE, 0x9BDC06A7, 0xC19BF174,
        0xE49B69C1, 0xEFBE4786, 0x0FC19DC6, 0x240CA1CC, 0x2DE92C6F, 0x4A7484AA, 0x5CB0A9DC, 0x76F988DA,
        0x983E5152, 0xA831C66D, 0xB00327C8, 0xBF597FC7, 0xC6E00BF3, 0xD5A79147, 0x06CA6351, 0x14292967,
        0x27B70A85, 0x2E1B2138, 0x4D2C6DFC, 0x53380D13, 0x650A7354, 0x766A0ABB, 0x81C2C92E, 0x92722C85,
        0xA2BFE8A1, 0xA81A664B, 0xC24B8B70, 0xC76C51A3, 0xD192E819, 0xD6990624, 0xF40E3585, 0x106AA070,
        0x19A4C116, 0x1E376C08, 0x2748774C, 0x34B0BCB5, 0x391C0CB3, 0x4ED8AA4A, 0x5B9CCA4F, 0x682E6FF3,
        0x748F82EE, 0x78A5636F, 0x84C87814, 0x8CC70208, 0x90BEFFFA, 0xA4506CEB, 0xBEF9A3F7, 0xC67178F2,
    )
    private val COEFFICIENT_TERMS = longArrayOf(
        0xE82E91B1, 0x44DE24C6, 0x3BEB65BC, 0xC89C5933, 0x8D1EC211, 0x314EF5D5, 0xE92491B1,
        0xF0A5B9D5, 0x06064CF3, 0xDA79FA60, 0xBED20560, 0x89F9C9F8, 0x35FA38C6, 0xCB49E3C6,
    )
    private val COEFFICIENT_OFFSETS = longArrayOf(
        0xB32700D0, 0x88DFF51D, 0x29514038, 0xB328CBDF, 0xD405B4D5, 0x26E55F3C, 0x607801C6,
        0x82B2C1FB, 0xFA51A3DA, 0x207CC464, 0x3C3C74F3, 0xD2D14B61, 0xBB04A2C1, 0xC5626167,
    )

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02X".format(it.toInt() and 0xff) }

    private fun rotate(value: Long, bits: Int): Long = ((value ushr bits) or (value shl (32 - bits))) and MASK
    private fun sigma0(value: Long): Long = rotate(value, 7) xor rotate(value, 18) xor (value ushr 3)
    private fun sigma1(value: Long): Long = rotate(value, 17) xor rotate(value, 19) xor (value ushr 10)

    private fun expand(words: MutableList<Long>, start: Int = 16) {
        for (i in start until 64) {
            words.add((sigma1(words[i - 2]) + words[i - 7] + sigma0(words[i - 15]) + words[i - 16]) and MASK)
        }
    }

    private fun rounds(state: LongArray, words: List<Long>, start: Int = 0): LongArray {
        var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
        var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
        for (i in start until 64) {
            val constant = if (start == 14 && i == 14) 0xC9DC06A7 else ROUND_CONSTANTS[i]
            val t1 = h + (rotate(e, 6) xor rotate(e, 11) xor rotate(e, 25)) + (g xor (e and (f xor g))) + constant + words[i]
            val t2 = (rotate(a, 2) xor rotate(a, 13) xor rotate(a, 22)) + ((a and b) or (c and (a or b)))
            h = g
            g = f
            f = e
            e = (d + t1) and MASK
            d = c
            c = b
            b = a
            a = (t1 + t2) and MASK
        }
        return longArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun addState(state: LongArray, working: LongArray) = LongArray(8) { (state[it] + working[it]) and MASK }

    private fun digest(data: List<Int>): ByteArray {
        val words = MutableList(14) { 0L }
        words.add(((data[0] shl 16) or (data[1] shl 8) or data[2]).toLong())
        words.add(((data[3].toLong() shl 24) or (data[4].toLong() shl 16) or (data[5].toLong() shl 8) or data[6].toLong()))
        for (i in 16 until 30) {
            var value = SCHEDULE[i - 16]
            value += sigma1(words[i - 2]) xor (if (i == 16) 0x000B92C0L else 0L)
            if (i - 7 >= 14) value += words[i - 7]
            if (i - 15 >= 14) value += sigma0(words[i - 15]) xor (if (i == 29) 0x059C0B80L else 0L)
            words.add(value and MASK)
        }
        words.add((sigma1(words[28]) + words[23] + sigma0(words[15]) + words[14] + 0xC9DC06A7 - ROUND_CONSTANTS[14]) and MASK)
        expand(words, 31)
        var state = addState(INITIAL, rounds(STATE, words, 14))

        val length = 57 + data.size
        // Dart's % is Euclidean for a positive divisor; Kotlin's is not.
        val padding = ByteArray(data.size - 7 + 1 + Math.floorMod(55 - length, 64) + 8)
        for (i in 7 until data.size) padding[i - 7] = data[i].toByte()
        padding[data.size - 7] = 0x80.toByte()
        val bytes = ByteBuffer.wrap(padding).order(ByteOrder.BIG_ENDIAN)
        bytes.putLong(padding.size - 8, length * 8L)
        var offset = 0
        while (offset < padding.size) {
            val block = MutableList(16) { bytes.getInt(offset + it * 4).toLong() and MASK }
            expand(block)
            state = addState(state, rounds(state, block))
            offset += 64
        }
        val result = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
        for (i in 0 until 8) result.putInt(i * 4, state[i].toInt())
        return result.array()
    }

    private fun checksum(data: List<Int>, digest: ByteArray): ByteArray {
        val hash = ByteBuffer.wrap(digest).order(ByteOrder.LITTLE_ENDIAN)
        val keyA = (hash.getInt(0).toLong() and MASK) or 1L
        val keyB = (hash.getInt(4).toLong() and MASK) or 1L
        val factors = longArrayOf(keyA * 0x82893933 and MASK, keyB * 0x82893933 and MASK)
        val terms = MutableList(14) { COEFFICIENT_TERMS[it] * factors[it % 2] and MASK }
        val offsets = COEFFICIENT_OFFSETS.toMutableList()
        val padded = ByteArray(((data.size + 1 + 7) / 8) * 8)
        for (i in data.indices) padded[i + 1] = data[i].toByte()
        val bytes = ByteBuffer.wrap(padded).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until padded.size / 4) {
            val word = bytes.getInt(i * 4).toLong() and MASK
            val even = i % 2 == 0
            var term = word * (if (even) keyA else keyB)
            var offset = rotate(word, 16) * (if (even) 0xEF0569FB else 0xC31713DB)
            if (i == 0) {
                term += 0xF2F90B1A * factors[0]
                offset += 0x0B1A0000
            }
            terms.add(term and MASK)
            offsets.add(offset and MASK)
        }
        var swapAcc = 0L
        var swapLast = 0L
        var state = 0L
        var acc = 0L
        var i = 0
        while (i < terms.size) {
            val x = terms[i]
            val y = terms[i + 1]
            var t = rotate((offsets[i] + x) and MASK, 16)
            var q = t * 0x76F50F34 and MASK
            swapAcc += (rotate(q, 16) * 0xC3EFEA97 + q) and MASK
            t = rotate((offsets[i + 1] + y) and MASK, 16)
            q = t * 0x1881CE3C and MASK
            swapLast = (rotate(q, 16) * 0x35BD1EC9 + q) and MASK
            swapAcc = (swapAcc + swapLast) and MASK
            state = (state * keyA + x) and MASK
            state = rotate(state, 16) * 0xCF98B111 and MASK
            state = rotate(state, 16) * 0x87085B9F and MASK
            state = rotate(state, 16) * 0x12CEB96D and MASK
            state = rotate(state, 16) * 0x257E1D83 and MASK
            acc += state
            t = (state * keyB + y) and MASK
            t = rotate(t, 16) * 0xA27416F5 and MASK
            t = rotate(t, 16) * 0xD38396FF and MASK
            state = rotate(rotate(t, 16) * 0x7C932B89 and MASK, 16) * 0xBFA49F69 and MASK
            acc = (acc + state) and MASK
            i += 2
        }
        val result = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        result.putInt(0, (swapLast xor state).toInt())
        result.putInt(4, (swapAcc xor acc).toInt())
        return result.array()
    }
}
