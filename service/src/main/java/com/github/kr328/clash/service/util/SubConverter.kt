package com.github.kr328.clash.service.util

import android.util.Base64
import org.json.JSONObject
import java.net.URLDecoder
import java.util.Locale
import com.github.kr328.clash.common.log.Log

object SubConverter {

    private val LINK_REGEX = """(vless|trojan|vmess|ss)://[^\s]+""".toRegex()

    private data class ParsedProxy(
        val name: String,
        val yamlBlock: String,
        val isTrash: Boolean
    )

    // Безопасный декодер (если провайдер прислал кривые % символы, он не упадет)
    private fun safeDecode(str: String): String {
        return try {
            URLDecoder.decode(str, "UTF-8")
        } catch (e: Exception) {
            str // Если декодировать не вышло, оставляем как есть
        }
    }

    fun convert(
        rawInput: String,
        pingUrl: String = "http://cp.cloudflare.com/generate_204",
        pingInterval: String = "300",
        pingTolerance: String = "150"
    ): String {
        Log.d("[KMS] --- НАЧАЛО КОНВЕРТАЦИИ ---")

        val input = rawInput.trim()
        if (input.isEmpty()) {
            Log.e("[KMS] Ошибка: Пустые данные от сервера")
            throw Exception("Empty subscription payload")
        }

        if (input.startsWith("proxies:") || input.contains("\nproxies:")) {
            Log.d("[KMS] Обнаружен готовый YAML, пропускаю парсинг")
            return input
        }

        val decoded = tryDecodeBase64(input)

        // Единый проход: ищет vless, vmess, trojan или ss и ставит перенос перед ними.
        // Никакого "откусывания" кусков слов больше не будет!
        val normalized = decoded.replace(Regex("(?i)(vless|trojan|vmess|ss)://"), "\n$1://")

        Log.d("[KMS] Декодирование успешно. Размер: ${normalized.length}")

        if (!normalized.contains("://")) {
            Log.e("[KMS] КРИТИЧЕСКАЯ ОШИБКА: В ответе нет ссылок!")
            throw Exception("Invalid format: No proxy links found")
        }

        val proxies = parseLinks(normalized)
        Log.d("[KMS] Найдено прокси: ${proxies.size}")

        if (proxies.isEmpty()) {
            Log.e("[KMS] Ошибка: Не удалось распознать ссылки")
            throw Exception("No supported proxies found")
        }

        return buildYaml(proxies, pingUrl, pingInterval, pingTolerance)
    }

    private fun tryDecodeBase64(input: String): String {
        return try {
            String(Base64.decode(input, Base64.DEFAULT), Charsets.UTF_8).trim()
        } catch (e: Exception) {
            input
        }
    }

    private fun parseLinks(decodedText: String): List<ParsedProxy> {
        val parsedList = mutableListOf<ParsedProxy>()
        val tag = "ClashMetaForAndroid" // Используем родной тег, чтобы точно увидеть

        val linkFinder = """(vless|trojan|vmess|ss)://[^\s]+""".toRegex()
        val matches = linkFinder.findAll(decodedText)

        Log.e( "[KMS] Потенциальных ссылок: ${matches.count()}")

        for ((index, match) in matches.withIndex()) {
            val fullLink = match.value.trim()
            if (fullLink.isEmpty()) continue

            if (index == 0) {
                // ПЕЧАТАЕМ ПЕРВУЮ ССЫЛКУ ЦЕЛИКОМ, ЧТОБЫ НАЙТИ БАГ
                Log.e("[KMS] СЫРАЯ ССЫЛКА 1: >$fullLink<")
            }

            val protocol = match.groupValues[1].lowercase()
            val payload = fullLink.substringAfter("://")

            try {
                val proxy = when (protocol) {
                    "vless", "trojan" -> {
                        val p = parseVlessOrTrojan(protocol, payload)
                        if (p == null) Log.e("[KMS] VLESS вернул NULL для: $payload")
                        p
                    }
                    "vmess" -> {
                        val p = parseVmess(payload)
                        if (p == null) Log.e("[KMS] VMESS вернул NULL для: $payload")
                        p
                    }
                    else -> null
                }

                if (proxy != null) {
                    parsedList.add(proxy)
                }
            } catch (e: Exception) {
                Log.e("[KMS] КРАШ ПАРСЕРА: ${e.message} на ссылке: $fullLink")
            }
        }
        return parsedList
    }

    private fun parseVlessOrTrojan(protocol: String, payload: String): ParsedProxy? {
        val rawName = if (payload.contains("#")) payload.substringAfter("#") else ""
        val dataPart = payload.substringBefore("#")

        val paramsStr = if (dataPart.contains("?")) dataPart.substringAfter("?") else ""
        val mainPart = dataPart.substringBefore("?")

        if (!mainPart.contains("@") || !mainPart.contains(":")) return null

        val uuidOrPass = mainPart.substringBefore("@")
        val addressPart = mainPart.substringAfter("@")
        val server = addressPart.substringBeforeLast(":")
        val port = addressPart.substringAfterLast(":").toIntOrNull() ?: 443

        val name = safeDecode(rawName).ifBlank { "${protocol.uppercase()}-$server" }

        val params = paramsStr.split("&").filter { it.contains("=") }.associate {
            val p = it.split("=")
            p[0] to safeDecode(p.getOrNull(1) ?: "")
        }

        val sb = StringBuilder()
        sb.append("  type: $protocol\n  server: '$server'\n  port: $port\n")

        if (protocol == "vless") sb.append("  uuid: '$uuidOrPass'\n")
        else sb.append("  password: '$uuidOrPass'\n")

        sb.append("  udp: true\n")

        val security = params["security"] ?: ""
        if (security == "reality" || security == "tls" || protocol == "trojan") {
            sb.append("  tls: true\n")
            params["sni"]?.let { if (it.isNotBlank()) sb.append("  servername: '$it'\n") }
        }

        params["flow"]?.let { if (it.isNotBlank()) sb.append("  flow: '$it'\n") }
        params["fp"]?.let { if (it.isNotBlank()) sb.append("  client-fingerprint: '$it'\n") }

        if (security == "reality") {
            sb.append("  reality-opts:\n    public-key: '${params["pbk"] ?: ""}'\n    short-id: '${params["sid"] ?: ""}'\n")
        }

        when (params["type"]) {
            "xhttp" -> sb.append("  network: xhttp\n  xhttp-opts:\n    path: '${params["path"] ?: "/"}'\n    mode: '${params["mode"] ?: "stream-up"}'\n")
            "ws" -> {
                sb.append("  network: ws\n  ws-opts:\n    path: '${params["path"] ?: "/"}'\n")
                params["host"]?.let { if (it.isNotBlank()) sb.append("    headers:\n      Host: $it\n") }
            }
            "grpc" -> sb.append("  network: grpc\n  grpc-opts:\n    grpc-service-name: '${params["serviceName"] ?: ""}'\n")
        }

        return ParsedProxy(name, sb.toString(), isTrash(name))
    }

    private fun parseVmess(b64Payload: String): ParsedProxy? {
        val jsonStr = try {
            String(Base64.decode(b64Payload, Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: Exception) {
            return null
        }
        val json = JSONObject(jsonStr)

        val name = json.optString("ps", "VMESS-${json.optString("add")}")
        val server = json.optString("add")
        val port = json.optString("port")
        val id = json.optString("id")
        val net = json.optString("net", "tcp")
        val tls = json.optString("tls", "")

        val sb = StringBuilder()
        sb.append("  type: vmess\n  server: '$server'\n  port: $port\n  uuid: '$id'\n  alterId: 0\n  cipher: auto\n  udp: true\n")

        if (tls == "tls") {
            sb.append("  tls: true\n")
            val sni = json.optString("sni")
            if (sni.isNotBlank()) sb.append("  servername: '$sni'\n")
        }

        when (net) {
            "ws" -> {
                sb.append("  network: ws\n  ws-opts:\n    path: '${json.optString("path", "/")}'\n")
                val host = json.optString("host")
                if (host.isNotBlank()) sb.append("    headers:\n      Host: $host\n")
            }
            "grpc" -> {
                sb.append("  network: grpc\n  grpc-opts:\n    grpc-service-name: '${json.optString("path")}'\n")
            }
        }

        return ParsedProxy(name, sb.toString(), isTrash(name))
    }

    private fun isTrash(name: String): Boolean {
        val nLower = name.lowercase(Locale.getDefault())
        return nLower.contains("russia") || nLower.contains("россия") || nLower.contains("бел") ||
                nLower.contains("осталось") || nLower.contains("рф") || nLower.contains("\uD83C\uDDF7\uD83C\uDDFA")
    }

    private fun buildYaml(proxies: List<ParsedProxy>, pingUrl: String, pingInt: String, pingTol: String): String {
        val sb = StringBuilder()

        // 1. Все прокси
        sb.append("proxies:\n")
        proxies.forEach {
            sb.append("- name: \"${it.name}\"\n")
            sb.append(it.yamlBlock)
        }

        sb.append("\nproxy-groups:\n")

        // --- ГРУППА 0: Главный селектор ---
        sb.append("- name: PROXY\n")
        sb.append("  type: select\n")
        sb.append("  icon: https://i.imgur.com/B3HrpPC.png\n")
        sb.append("  proxies:\n")
        sb.append("    - Overseas-Auto\n")   // Авто-заграница

        // Показываем кнопку RU-Auto только если такие серверы вообще есть в подписке
        val hasTrash = proxies.any { it.isTrash }
        if (hasTrash) {
            sb.append("    - RU/RB-Auto\n")
        }

        sb.append("    - Manual-Select\n") // Ручной выбор из всех

        // --- ГРУППА 1: Overseas-Auto (без RU/RB) ---
        sb.append("\n- name: Overseas-Auto\n")
        sb.append("  type: url-test\n")
        sb.append("  url: $pingUrl\n")
        sb.append("  interval: $pingInt\n")
        sb.append("  tolerance: $pingTol\n")
        sb.append("  proxies:\n")
        val overseas = proxies.filter { !it.isTrash }
        if (overseas.isEmpty()) {
            proxies.forEach { sb.append("    - \"${it.name}\"\n") }
        } else {
            overseas.forEach { sb.append("    - \"${it.name}\"\n") }
        }

        // --- ГРУППА 2: RU/RB-Auto (только RU/RB) ---
        if (hasTrash) {
            sb.append("\n- name: RU/RB-Auto\n")
            sb.append("  type: url-test\n")
            sb.append("  url: $pingUrl\n")
            sb.append("  interval: $pingInt\n")
            sb.append("  tolerance: $pingTol\n")
            sb.append("  proxies:\n")
            proxies.filter { it.isTrash }.forEach { sb.append("    - \"${it.name}\"\n") }
        }

        // --- ГРУППА 3: Manual-Select (Все серверы списком) ---
        sb.append("\n- name: Manual-Select\n")
        sb.append("  type: select\n")
        sb.append("  proxies:\n")
        proxies.forEach { sb.append("    - \"${it.name}\"\n") }

        // --- ПРАВИЛА ---
        sb.append("""
        |
        |rules:
        |  - DOMAIN,rezvorck.github.io,REJECT
        |  - DOMAIN,tigr1234566.github.io,REJECT
        |  - MATCH,PROXY
        |""".trimMargin()
        )

        return sb.toString()
    }
}