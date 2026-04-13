package com.github.kr328.clash.service.util

import java.io.File
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

object SubConverter {

    // ОПТИМИЗАЦИЯ: Теперь конвертер пишет результат напрямую в файл (Streaming IO)
    fun convertToFile(
        rawInputBytes: ByteArray,
        params: Map<String, String>,
        outputFile: File
    ) {
        DecodeUtils.dLog("=== SUB CONVERTER START ===")

        // Умная распаковка: Если это Gzip - распаковываем, иначе читаем как строку
        val isGzipped = rawInputBytes.size >= 2 && rawInputBytes[0].toInt() == 0x1F && (rawInputBytes[1].toInt() and 0xFF) == 0x8B
        val rawInput = if (isGzipped) {
            GZIPInputStream(ByteArrayInputStream(rawInputBytes)).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } else {
            String(rawInputBytes, Charsets.UTF_8)
        }

        val input = rawInput.trim()
        if (input.isEmpty()) throw Exception("Empty input")

        // Если это уже готовый YAML - просто копируем как есть
        if (input.contains("proxies:") && input.contains("proxy-groups:")) {
            outputFile.writeText(input, Charsets.UTF_8)
            return
        }

        var proxies = ProxyParser.extractProxiesFromText(input)

        if (proxies.isEmpty()) {
            val decoded = DecodeUtils.tryDecodeBase64(input)
            if (decoded.isNotBlank()) {
                if (decoded.contains("://")) proxies = ProxyParser.extractProxiesFromText(decoded)
                else if (decoded.trim().startsWith("[")) proxies = ProxyParser.parseV2rayJsonArray(decoded.trim())
            }
        }

        if (proxies.isEmpty() && input.startsWith("[")) proxies = ProxyParser.parseV2rayJsonArray(input)
        if (proxies.isEmpty()) throw Exception("MegaConverter: No supported proxies found!")

        // Открываем потоковую запись в файл с принудительным UTF-8 (ФИКС КРАКОЗЯБР)
        outputFile.bufferedWriter(Charsets.UTF_8).use { writer ->
            YamlBuilder.buildYamlStream(proxies, params, writer)
        }
    }
}