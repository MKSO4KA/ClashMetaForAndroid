package com.github.kr328.clash.service.util

object SubConverter {

    fun convert(
        rawInput: String,
        pingUrl: String = "http://cp.cloudflare.com/generate_204",
        pingInterval: String = "300",
        pingTolerance: String = "150"
    ): String {
        DecodeUtils.dLog("=== SUB CONVERTER START ===")
        DecodeUtils.dLog("Raw Input Length: ${rawInput.length}")

        // ШАГ 0: Проверяем, что за байты к нам пришли
        DecodeUtils.inspectBytes(rawInput)

        val input = rawInput.trim()
        if (input.isEmpty()) throw Exception("Empty input")

        if (input.contains("proxies:") && input.contains("proxy-groups:")) return input

        // ШАГ 1: Пробуем найти ссылки прямо в тексте (на случай, если это не Base64)
        var proxies = ProxyParser.extractProxiesFromText(input)

        // ШАГ 2: Если ссылок нет, пробуем Base64 путь
        if (proxies.isEmpty()) {
            DecodeUtils.dLog("No direct links, trying Base64 decode...")
            val decoded = DecodeUtils.tryDecodeBase64(input)

            if (decoded.isNotBlank()) {
                if (decoded.contains("://")) {
                    DecodeUtils.dLog("Found links inside Base64!")
                    proxies = ProxyParser.extractProxiesFromText(decoded)
                } else if (decoded.trim().startsWith("[")) {
                    DecodeUtils.dLog("Found JSON Array inside Base64!")
                    proxies = ProxyParser.parseV2rayJsonArray(decoded.trim())
                }
            }
        }

        // ШАГ 3: Если это прямой JSON массив
        if (proxies.isEmpty() && input.startsWith("[")) {
            proxies = ProxyParser.parseV2rayJsonArray(input)
        }

        if (proxies.isEmpty()) {
            DecodeUtils.eLog("Final result: No proxies found. Raw input was: ${input.take(50)}...")
            throw Exception("MegaConverter: No supported proxies found!")
        }

        return YamlBuilder.buildYaml(proxies, pingUrl, pingInterval, pingTolerance)
    }
}