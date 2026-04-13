package com.github.kr328.clash.service.util

object SubConverter {

    fun convert(
        rawInput: String,
        params: Map<String, String> // Передаем все параметры профиля сразу
    ): String {
        DecodeUtils.dLog("=== SUB CONVERTER START ===")
        DecodeUtils.inspectBytes(rawInput)

        val input = rawInput.trim()
        if (input.isEmpty()) throw Exception("Empty input")
        if (input.contains("proxies:") && input.contains("proxy-groups:")) return input

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

        // Мы больше не удаляем прокси здесь! Мы передаем список забаненных в YamlBuilder

        return YamlBuilder.buildYaml(proxies, params)
    }
}