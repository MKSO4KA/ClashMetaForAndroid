package com.github.kr328.clash.service.util

import android.util.Base64
import java.net.URLDecoder
import java.util.Locale
import com.github.kr328.clash.common.log.Log

object DecodeUtils {

    // Включатель логов (true = пишет всё, false = молчит)
    const val ENABLE_DEBUG_LOGS = false

    fun dLog(message: String) {
        if (ENABLE_DEBUG_LOGS) Log.d("[MegaKMS] $message")
    }

    fun eLog(message: String) {
        if (ENABLE_DEBUG_LOGS) Log.e("[MegaKMS] ERROR: $message")
    }

    // НОВЫЙ МЕТОД: Инспекция байтов
    fun inspectBytes(input: String) {
        try {
            val bytes = input.toByteArray(Charsets.UTF_8)
            val hex = bytes.take(20).joinToString(" ") { "%02X".format(it) }
            dLog("HEX DUMP (First 20 bytes): $hex")

            // Проверка на GZIP (заголовок GZIP всегда начинается с 1F 8B)
            if (bytes.size >= 2 && bytes[0].toInt() == 0x1F && (bytes[1].toInt() and 0xFF) == 0x8B) {
                eLog("DETECTED GZIP! The input data is compressed. You need to decompress it before parsing.")
            }
        } catch (e: Exception) {
            eLog("inspectBytes failed: ${e.message}")
        }
    }

    fun tryDecodeBase64(input: String): String {
        // Перед декодированием вычистим ВООБЩЕ ВСЁ, что не является символом Base64
        val allowedBase64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=-_".toSet()
        val clean = input.filter { it in allowedBase64 }

        return try {
            val bytes = Base64.decode(clean, Base64.DEFAULT)
            String(bytes, Charsets.UTF_8).trim()
        } catch (e: Exception) {
            try {
                val bytes = Base64.decode(clean, Base64.URL_SAFE)
                String(bytes, Charsets.UTF_8).trim()
            } catch (e2: Exception) {
                ""
            }
        }
    }

    fun safeUrlDecode(str: String): String {
        if (str.isBlank()) return ""
        return try {
            URLDecoder.decode(str.replace("+", "%2B"), "UTF-8")
        } catch (e: Exception) {
            str
        }
    }

    fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val map = mutableMapOf<String, String>()
        val pairs = query.split("&")
        for (pair in pairs) {
            val idx = pair.indexOf("=")
            if (idx > 0) {
                map[safeUrlDecode(pair.substring(0, idx))] = safeUrlDecode(pair.substring(idx + 1))
            } else if (pair.isNotEmpty()) {
                map[safeUrlDecode(pair)] = ""
            }
        }
        return map
    }

    fun isTrash(name: String): Boolean {
        val nLower = name.lowercase(Locale.getDefault())
        return nLower.contains("russia") || nLower.contains("россия") ||
                nLower.contains("бел") || nLower.contains("осталось") ||
                nLower.contains("рф") || nLower.contains("\uD83C\uDDF7\uD83C\uDDFA") ||
                nLower.contains("direct") || nLower.contains("expire")
    }
}