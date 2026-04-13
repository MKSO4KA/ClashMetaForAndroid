package com.github.kr328.clash.service.util

import android.util.Base64
import com.github.kr328.clash.common.log.Log
import java.net.URLDecoder
import java.util.Locale

object DecodeUtils {
    const val ENABLE_DEBUG_LOGS = false

    // ОПТИМИЗАЦИЯ: Регулярки компилируются 1 раз (O(1))
    // Ловит: Бел, Бел., Бела, Белый, Белые, white, whitelist, whitelists, локальный, local
    private val WHITELIST_REGEX = "(?i)(\\bбел[а-я\\.]{0,2}\\b|\\bwhite\\s?(list(ed|s)?)?\\b|\\bсписок\\b|\\bсписки\\b|\\bлокальн[а-я]{0,2}\\b|\\blocal\\b)".toRegex()
    private val TRASH_REGEX = "(?i)(тех.*работ|обслуживан|maintenance|dead|error|timeout|ост.*0|expire.*0|out of date|истек|expired|limit)".toRegex()
    private val RURB_GEOGRAPHY_REGEX = "(?i)(\\bru\\b|russia|росси|\\brb\\b|belarus|беларус|\\bby\\b|\uD83C\uDDF7\uD83C\uDDFA|\uD83C\uDDE7\uD83C\uDDFE)".toRegex()

    fun dLog(message: String) { if (ENABLE_DEBUG_LOGS) Log.d("[MegaKMS] $message") }
    fun eLog(message: String) { if (ENABLE_DEBUG_LOGS) Log.e("[MegaKMS] ERROR: $message") }

    fun inspectBytes(input: String) {
        try {
            val bytes = input.toByteArray(Charsets.UTF_8)
            if (bytes.size >= 2 && bytes[0].toInt() == 0x1F && (bytes[1].toInt() and 0xFF) == 0x8B) {
                eLog("DETECTED GZIP! The input data is compressed.")
            }
        } catch (e: Exception) { eLog("inspectBytes failed: ${e.message}") }
    }

    fun tryDecodeBase64(input: String): String {
        val allowedBase64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=-_".toSet()
        val clean = input.filter { it in allowedBase64 }
        return try {
            String(Base64.decode(clean, Base64.DEFAULT), Charsets.UTF_8).trim()
        } catch (e: Exception) {
            try {
                String(Base64.decode(clean, Base64.URL_SAFE), Charsets.UTF_8).trim()
            } catch (e2: Exception) { "" }
        }
    }

    fun safeUrlDecode(str: String): String {
        if (str.isBlank()) return ""
        return try { URLDecoder.decode(str.replace("+", "%2B"), "UTF-8") } catch (e: Exception) { str }
    }

    fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val map = mutableMapOf<String, String>()
        for (pair in query.split("&")) {
            val idx = pair.indexOf("=")
            if (idx > 0) map[safeUrlDecode(pair.substring(0, idx))] = safeUrlDecode(pair.substring(idx + 1))
            else if (pair.isNotEmpty()) map[safeUrlDecode(pair)] = ""
        }
        return map
    }

    fun isTrash(name: String): Boolean {
        if (name.isBlank()) return true
        val nLower = name.lowercase(Locale.getDefault())
        if (WHITELIST_REGEX.containsMatchIn(nLower)) return false // Защита белых списков
        return TRASH_REGEX.containsMatchIn(nLower) || nLower.contains("direct") || nLower.contains("reject")
    }

    fun isRuRb(name: String): Boolean {
        val nLower = name.lowercase(Locale.getDefault())
        if (WHITELIST_REGEX.containsMatchIn(nLower)) return true // Приоритет функционала
        return RURB_GEOGRAPHY_REGEX.containsMatchIn(nLower)      // Затем география
    }
}