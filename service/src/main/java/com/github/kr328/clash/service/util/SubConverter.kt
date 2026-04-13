package com.github.kr328.clash.service.util

import java.io.File

object SubConverter {

    // ОПТИМИЗАЦИЯ: Теперь конвертер пишет результат напрямую в файл (Streaming IO)
    fun convertToFile(
        rawInput: String,
        params: Map<String, String>,
        outputFile: File
    ) {
        DecodeUtils.dLog("=== SUB CONVERTER START ===")

        val input = rawInput.trim()
        if (input.isEmpty()) throw Exception("Empty input")

        // Если это уже готовый YAML - просто копируем как есть
        if (input.contains("proxies:") && input.contains("proxy-groups:")) {
            outputFile.writeText(input)
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

        // Открываем потоковую запись в файл
        outputFile.bufferedWriter().use { writer ->
            YamlBuilder.buildYamlStream(proxies, params, writer)
        }
    }
}