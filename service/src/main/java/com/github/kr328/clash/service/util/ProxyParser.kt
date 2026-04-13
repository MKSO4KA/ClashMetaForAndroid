package com.github.kr328.clash.service.util

import org.json.JSONArray
import org.json.JSONObject

object ProxyParser {

    /**
     * Извлекает все ссылки из любого текста.
     */
    fun extractProxiesFromText(text: String): List<ProxyNode> {
        val result = mutableListOf<ProxyNode>()
        val linkRegex = """(?i)(vless|vmess|trojan|ss|ssr|hysteria2|hy2|tuic)://[^\s,;|]+""".toRegex()
        val matches = linkRegex.findAll(text)

        DecodeUtils.dLog("extractProxiesFromText: found ${matches.count()} potential links")

        for (match in matches) {
            val node = parseUri(match.value.trim())
            if (node != null) result.add(node)
        }
        return result
    }

    /**
     * Парсер JSON массива (формат V2Ray Array).
     */
    fun parseV2rayJsonArray(jsonStr: String): List<ProxyNode> {
        val list = mutableListOf<ProxyNode>()
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val config = jsonArray.optJSONObject(i) ?: continue
                val rawName = config.optString("remarks", "Unnamed-Proxy")
                val name = DecodeUtils.safeUrlDecode(rawName)

                val outbounds = config.optJSONArray("outbounds") ?: continue
                var proxyOutbound: JSONObject? = null

                for (j in 0 until outbounds.length()) {
                    val ob = outbounds.optJSONObject(j) ?: continue
                    val protocol = ob.optString("protocol")
                    if (protocol == "vless" || protocol == "vmess" || protocol == "trojan") {
                        proxyOutbound = ob
                        break
                    }
                }

                if (proxyOutbound == null) continue

                val protocol = proxyOutbound.optString("protocol")
                val settings = proxyOutbound.optJSONObject("settings") ?: continue
                val stream = proxyOutbound.optJSONObject("streamSettings") ?: JSONObject()
                val vnext = settings.optJSONArray("vnext")?.optJSONObject(0) ?: continue
                val user = vnext.optJSONArray("users")?.optJSONObject(0) ?: continue

                val node = ProxyNode(name = name, isTrash = DecodeUtils.isTrash(name))

                node.type = when (protocol) {
                    "vless" -> ProxyType.VLESS
                    "vmess" -> ProxyType.VMESS
                    "trojan" -> ProxyType.TROJAN
                    else -> ProxyType.UNKNOWN
                }

                node.server = vnext.optString("address")
                node.port = vnext.optInt("port", 443)
                node.alterId = if (protocol == "vless") -1 else 0

                if (node.type == ProxyType.TROJAN) {
                    node.password = user.optString("password")
                } else {
                    node.uuid = user.optString("id")
                }

                node.network = stream.optString("network", "tcp")
                node.flow = user.optString("flow", "")
                node.cipher = user.optString("encryption", "none").ifBlank { "auto" }

                when (node.network) {
                    "grpc" -> node.grpcServiceName = stream.optJSONObject("grpcSettings")?.optString("serviceName") ?: ""
                    "ws" -> {
                        val ws = stream.optJSONObject("wsSettings")
                        node.wsPath = ws?.optString("path") ?: "/"
                        val host = ws?.optJSONObject("headers")?.optString("Host")
                        if (!host.isNullOrBlank()) node.wsHeaders = mapOf("Host" to host)
                    }
                }

                node.security = stream.optString("security", "none")
                if (node.security == "reality" || node.security == "tls") {
                    node.tls = true
                    val secSettings = stream.optJSONObject(if (node.security == "reality") "realitySettings" else "tlsSettings")
                    if (secSettings != null) {
                        node.sni = secSettings.optString("serverName")
                        node.fingerprint = secSettings.optString("fingerprint", "chrome")
                        if (node.security == "reality") {
                            node.isReality = true
                            node.realityPubKey = secSettings.optString("publicKey")
                            node.realityShortId = secSettings.optString("shortId")
                        }
                    }
                }
                list.add(node)
            }
        } catch (e: Exception) {
            DecodeUtils.eLog("parseV2rayJsonArray failed: ${e.message}")
        }
        return list
    }

    private fun parseUri(uri: String): ProxyNode? {
        val link = uri.trim()
        return try {
            when {
                link.startsWith("vless://", true) -> parseVlessOrTrojan(link, ProxyType.VLESS)
                link.startsWith("trojan://", true) -> parseVlessOrTrojan(link, ProxyType.TROJAN)
                link.startsWith("vmess://", true) -> parseVmess(link)
                link.startsWith("ss://", true) -> parseShadowsocks(link)
                link.startsWith("ssr://", true) -> parseSSR(link)
                link.startsWith("hysteria2://", true) || link.startsWith("hy2://", true) -> parseHysteria2(link)
                link.startsWith("tuic://", true) -> parseTuic(link)
                else -> null
            }
        } catch (e: Exception) {
            DecodeUtils.eLog("parseUri CRASH for link: ${link.take(20)}... Error: ${e.message}")
            null
        }
    }

    private fun parseVlessOrTrojan(uri: String, type: ProxyType): ProxyNode? {
        val payload = uri.substringAfter("://")
        val rawName = payload.substringAfter("#", "")
        val dataPart = payload.substringBefore("#")

        val paramsStr = dataPart.substringAfter("?", "")
        val mainPart = dataPart.substringBefore("?")

        if (!mainPart.contains("@") || !mainPart.contains(":")) return null

        val uuidOrPass = mainPart.substringBefore("@")
        val addressPart = mainPart.substringAfter("@")
        val server = addressPart.substringBeforeLast(":")
        val port = addressPart.substringAfterLast(":").toIntOrNull() ?: 443

        val name = DecodeUtils.safeUrlDecode(rawName).ifBlank { "${type.name}-$server" }
        val params = DecodeUtils.parseQuery(paramsStr)

        val node = ProxyNode(
            name = name,
            type = type,
            server = server,
            port = port,
            isTrash = DecodeUtils.isTrash(name)
        )

        if (type == ProxyType.VLESS) node.uuid = uuidOrPass else node.password = uuidOrPass

        val security = params["security"] ?: ""
        if (security == "reality" || security == "tls" || type == ProxyType.TROJAN) {
            node.tls = true
            node.sni = params["sni"] ?: params["peer"] ?: ""
            node.fingerprint = params["fp"] ?: ""
            node.alpn = params["alpn"]?.split(",") ?: emptyList()
            node.skipCertVerify = params["allowInsecure"] == "1"

            if (security == "reality") {
                node.isReality = true
                node.realityPubKey = params["pbk"] ?: ""
                node.realityShortId = params["sid"] ?: ""
            }
        }

        node.flow = params["flow"] ?: ""
        node.network = params["type"] ?: "tcp"
        when (node.network) {
            "ws" -> {
                node.wsPath = params["path"] ?: "/"
                val host = params["host"]
                if (!host.isNullOrBlank()) node.wsHeaders = mapOf("Host" to host)
            }
            "grpc" -> node.grpcServiceName = params["serviceName"] ?: params["authority"] ?: ""
            "xhttp", "httpupgrade" -> {
                node.xhttpPath = params["path"] ?: "/"
                node.xhttpMode = params["mode"] ?: "auto"
                val host = params["host"]
                if (!host.isNullOrBlank()) node.wsHeaders = mapOf("Host" to host)
            }
        }
        return node
    }

    private fun parseVmess(uri: String): ProxyNode? {
        val b64Payload = uri.substringAfter("://")
        val jsonStr = DecodeUtils.tryDecodeBase64(b64Payload)
        if (jsonStr.isBlank() || !jsonStr.startsWith("{")) return null

        val json = JSONObject(jsonStr)
        val server = json.optString("add")
        val port = json.optInt("port", 443)
        if (server.isBlank() || port == 0) return null

        val name = DecodeUtils.safeUrlDecode(json.optString("ps", "VMESS-$server"))

        val node = ProxyNode(
            name = name,
            type = ProxyType.VMESS,
            server = server,
            port = port,
            uuid = json.optString("id"),
            alterId = json.optInt("aid", 0),
            cipher = json.optString("scy", "auto").ifBlank { "auto" },
            network = json.optString("net", "tcp"),
            tls = json.optString("tls") == "tls" || json.optString("tls") == "reality",
            sni = json.optString("sni", json.optString("host")),
            isTrash = DecodeUtils.isTrash(name)
        )

        when (node.network) {
            "ws" -> {
                node.wsPath = json.optString("path", "/")
                val host = json.optString("host")
                if (host.isNotBlank()) node.wsHeaders = mapOf("Host" to host)
            }
            "grpc" -> node.grpcServiceName = json.optString("path")
        }
        return node
    }

    private fun parseShadowsocks(uri: String): ProxyNode? {
        val payload = uri.substringAfter("://")
        val rawName = payload.substringAfter("#", "")
        val dataPart = payload.substringBefore("#")

        var methodPass = ""
        var hostPort = ""
        var paramsStr = ""

        if (dataPart.contains("@")) {
            methodPass = DecodeUtils.tryDecodeBase64(dataPart.substringBefore("@"))
            val endPart = dataPart.substringAfter("@")
            hostPort = endPart.substringBefore("?")
            paramsStr = endPart.substringAfter("?", "")
        } else {
            val decoded = DecodeUtils.tryDecodeBase64(dataPart)
            methodPass = decoded.substringBefore("@")
            hostPort = decoded.substringAfter("@")
        }

        if (!methodPass.contains(":") || !hostPort.contains(":")) return null

        val server = hostPort.substringBeforeLast(":")
        val port = hostPort.substringAfterLast(":").toIntOrNull() ?: return null
        val name = DecodeUtils.safeUrlDecode(rawName).ifBlank { "SS-$server" }

        val node = ProxyNode(
            name = name,
            type = ProxyType.SHADOWSOCKS,
            server = server,
            port = port,
            cipher = methodPass.substringBefore(":"),
            password = methodPass.substringAfter(":"),
            isTrash = DecodeUtils.isTrash(name)
        )

        val params = DecodeUtils.parseQuery(paramsStr)
        val pluginStr = DecodeUtils.safeUrlDecode(params["plugin"] ?: "")
        if (pluginStr.isNotBlank()) {
            val pluginParts = pluginStr.split(";")
            node.plugin = pluginParts[0]
            val pOpts = mutableMapOf<String, String>()
            for (i in 1 until pluginParts.size) {
                val p = pluginParts[i].split("=")
                if (p.size == 2) pOpts[p[0]] = p[1]
            }
            if (node.plugin == "simple-obfs") {
                node.plugin = "obfs-local"
                node.obfs = pOpts["obfs"] ?: "http"
                node.obfsParam = pOpts["obfs-host"] ?: ""
            }
            node.pluginOpts = pOpts
        }
        return node
    }

    private fun parseHysteria2(uri: String): ProxyNode? {
        val data = uri.substringAfter("://")
        val name = DecodeUtils.safeUrlDecode(data.substringAfter("#", ""))
        val main = data.substringBefore("#")
        val query = DecodeUtils.parseQuery(main.substringAfter("?", ""))
        val authAndHost = main.substringBefore("?")
        val pass = authAndHost.substringBefore("@", "")
        val hostPort = authAndHost.substringAfter("@")
        val server = hostPort.substringBeforeLast(":")
        val port = hostPort.substringAfterLast(":").toIntOrNull() ?: 443

        return ProxyNode(
            name = name.ifBlank { "HY2-$server" },
            type = ProxyType.HYSTERIA2,
            server = server,
            port = port,
            password = pass,
            sni = query["sni"] ?: "",
            skipCertVerify = query["insecure"] == "1",
            obfs = query["obfs"] ?: "",
            obfsPassword = query["obfs-password"] ?: "",
            alpn = query["alpn"]?.split(",") ?: emptyList(),
            fingerprint = query["pinSHA256"] ?: "",
            isTrash = DecodeUtils.isTrash(name)
        )
    }

    private fun parseTuic(uri: String): ProxyNode? {
        val data = uri.substringAfter("://")
        val name = DecodeUtils.safeUrlDecode(data.substringAfter("#", ""))
        val main = data.substringBefore("#")
        val query = DecodeUtils.parseQuery(main.substringAfter("?", ""))
        val authAndHost = main.substringBefore("?")
        val uuidToken = authAndHost.substringBefore("@")
        val hostPort = authAndHost.substringAfter("@")
        val server = hostPort.substringBeforeLast(":")
        val port = hostPort.substringAfterLast(":").toIntOrNull() ?: 443

        return ProxyNode(
            name = name.ifBlank { "TUIC-$server" },
            type = ProxyType.TUIC,
            server = server,
            port = port,
            uuid = uuidToken.substringBefore(":"),
            password = uuidToken.substringAfter(":", ""),
            sni = query["sni"] ?: "",
            congestionController = query["congestion_control"] ?: "bbr",
            udpRelayMode = query["udp_relay_mode"] ?: "quic",
            alpn = query["alpn"]?.split(",") ?: emptyList(),
            skipCertVerify = query["allow_insecure"] == "1",
            isTrash = DecodeUtils.isTrash(name)
        )
    }

    private fun parseSSR(uri: String): ProxyNode? {
        val decoded = DecodeUtils.tryDecodeBase64(uri.substringAfter("://"))
        val mainPart = decoded.substringBefore("/?")
        val params = DecodeUtils.parseQuery(decoded.substringAfter("/?", ""))
        val parts = mainPart.split(":")
        if (parts.size < 6) return null

        val server = parts[0]
        val port = parts[1].toIntOrNull() ?: return null
        val protocol = parts[2]
        val method = parts[3]
        val obfs = parts[4]
        val password = DecodeUtils.tryDecodeBase64(parts[5])
        val name = DecodeUtils.tryDecodeBase64(params["remarks"] ?: "").ifBlank { "SSR-$server" }

        return ProxyNode(
            name = name,
            type = ProxyType.SSR,
            server = server,
            port = port,
            cipher = method,
            password = password,
            protocol = protocol,
            protocolParam = DecodeUtils.tryDecodeBase64(params["protoparam"] ?: ""),
            obfs = obfs,
            obfsParam = DecodeUtils.tryDecodeBase64(params["obfsparam"] ?: ""),
            isTrash = DecodeUtils.isTrash(name)
        )
    }
}