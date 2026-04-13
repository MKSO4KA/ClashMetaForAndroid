package com.github.kr328.clash.service.util

import java.io.Writer

object YamlBuilder {

    // ОПТИМИЗАЦИЯ: Пишем напрямую в Writer (Stream), минуя гигантские String Builder'ы.
    fun buildYamlStream(
        proxies: List<ProxyNode>,
        params: Map<String, String>,
        writer: Writer
    ) {
        DecodeUtils.dLog("YamlBuilder: Start streaming YAML for ${proxies.size} proxies")

        // Быстрая O(N) дедупликация имен без аллокации лишних строк
        val nameSet = mutableSetOf<String>()
        proxies.forEach { node ->
            var safeName = node.name.replace("\"", "").replace("\n", "").trim()
            if (safeName.isBlank()) safeName = "Proxy"
            var finalName = safeName
            var counter = 1
            while (!nameSet.add(finalName)) {
                finalName = "$safeName $counter"
                counter++
            }
            node.name = finalName
            node.isTrash = DecodeUtils.isTrash(node.name)
        }

        val blacklistSet = (params["Blacklist"] ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val whitelistSet = (params["Whitelist"] ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

        val activeProxies = mutableListOf<ProxyNode>()
        val blacklistedProxies = mutableListOf<ProxyNode>()
        val ruRbProxies = mutableListOf<ProxyNode>()
        val overseasProxies = mutableListOf<ProxyNode>()

        // Однопроходная O(N) сортировка по корзинам
        for (node in proxies) {
            val isManualBan = blacklistSet.contains(node.name)
            val isManualWhite = whitelistSet.contains(node.name)

            if (isManualBan || (node.isTrash && !isManualWhite)) {
                blacklistedProxies.add(node)
            } else {
                activeProxies.add(node)
                if (DecodeUtils.isRuRb(node.name)) {
                    ruRbProxies.add(node)
                } else {
                    overseasProxies.add(node)
                }
            }
        }

        val hasRuRb = ruRbProxies.isNotEmpty()
        val pingUrl = params["PingUrl"] ?: "http://cp.cloudflare.com/generate_204"
        val pingInt = params["Ping"] ?: "300"
        val pingTol = params["Tolerance"] ?: "150"

        // 1. ГЛОБАЛЬНЫЕ ПАРАМЕТРЫ (Local Proxy для Телеграма и LAN)
        writer.write("mixed-port: 7893\n")
        writer.write("allow-lan: true\n")
        writer.write("bind-address: \"*\"\n")
        writer.write("mode: rule\n")
        writer.write("log-level: info\n")
        writer.write("ipv6: false\n\n")

        // 2. ПИШЕМ ПРОКСИ В ПОТОК
        writer.write("proxies:\n")
        proxies.forEach { node ->
            writeProxyBlock(node, params, writer)
        }

        // 3. ПИШЕМ ГРУППЫ В ПОТОК
        writer.write("\nproxy-groups:\n")

        writer.write("- name: PROXY\n  type: select\n  proxies:\n")
        if (activeProxies.isNotEmpty()) {
            writer.write("    - Overseas-Auto\n")
            if (hasRuRb) writer.write("    - RU/RB-Auto\n")
            writer.write("    - Manual-Select\n")
        } else {
            writer.write("    - DIRECT\n") // Защита от пустого списка
        }

        writer.write("\n- name: Overseas-Auto\n  type: url-test\n  url: \"$pingUrl\"\n  interval: $pingInt\n  tolerance: $pingTol\n  proxies:\n")
        val targetOverseas = if (overseasProxies.isEmpty()) activeProxies else overseasProxies
        if (targetOverseas.isNotEmpty()) {
            targetOverseas.forEach { writer.write("    - \"${it.name}\"\n") }
        } else {
            writer.write("    - DIRECT\n")
        }

        if (hasRuRb) {
            writer.write("\n- name: RU/RB-Auto\n  type: url-test\n  url: \"$pingUrl\"\n  interval: $pingInt\n  tolerance: $pingTol\n  proxies:\n")
            ruRbProxies.forEach { writer.write("    - \"${it.name}\"\n") }
        }

        writer.write("\n- name: Manual-Select\n  type: select\n  proxies:\n")
        if (activeProxies.isNotEmpty()) {
            activeProxies.forEach { writer.write("    - \"${it.name}\"\n") }
        } else {
            writer.write("    - DIRECT\n")
        }

        writer.write("\n- name: Blacklisted\n  type: select\n  proxies:\n")
        if (blacklistedProxies.isNotEmpty()) {
            blacklistedProxies.forEach { writer.write("    - \"${it.name}\"\n") }
        } else {
            writer.write("    - DIRECT\n")
        }

        // 4. ПРАВИЛА (Фикс вылета ColorOS: REJECT -> REJECT-DROP)
        writer.write("\n\nrules:\n")
        writer.write("  - DOMAIN,rezvorck.github.io,REJECT-DROP\n")
        writer.write("  - DOMAIN,tigr1234566.github.io,REJECT-DROP\n")
        writer.write("  - MATCH,PROXY\n")
    }

    private fun writeProxyBlock(node: ProxyNode, params: Map<String, String>, writer: Writer) {
        writer.write("  - name: \"${node.name}\"\n    server: \"${node.server}\"\n    port: ${node.port}\n    type: ${node.type.name.lowercase()}\n")
        if (node.udp) writer.write("    udp: true\n")

        val supportsAntiDpi = node.type == ProxyType.VLESS || node.type == ProxyType.VMESS || node.type == ProxyType.TROJAN

        if (supportsAntiDpi && params["Mux"] == "1") {
            val maxCon = params["MuxCon"] ?: "8"
            val minStr = params["MuxMin"] ?: "4"
            writer.write("    smux:\n      enabled: true\n      protocol: h2mux\n      max-connections: $maxCon\n      min-streams: $minStr\n")
        }

        when (node.type) {
            ProxyType.SHADOWSOCKS -> {
                writer.write("    cipher: \"${node.cipher}\"\n    password: \"${node.password}\"\n")
                if (node.plugin.isNotBlank()) {
                    writer.write("    plugin: ${node.plugin}\n    plugin-opts:\n")
                    node.pluginOpts.forEach { (k, v) -> writer.write("      $k: \"$v\"\n") }
                    if (node.obfs.isNotBlank()) writer.write("      mode: ${node.obfs}\n      host: \"${node.obfsParam}\"\n")
                }
            }
            ProxyType.VLESS -> {
                writer.write("    uuid: \"${node.uuid}\"\n")
                // Согласно документации Mihomo, для gRPC flow должен быть пустым
                if (node.network != "grpc" && node.flow.isNotBlank()) {
                    writer.write("    flow: ${node.flow}\n")
                }
                writeTlsAndTransportStr(node, supportsAntiDpi, params, writer)
            }
            ProxyType.VMESS -> {
                writer.write("    uuid: \"${node.uuid}\"\n    alterId: ${node.alterId}\n    cipher: \"${node.cipher}\"\n")
                writeTlsAndTransportStr(node, supportsAntiDpi, params, writer)
            }
            ProxyType.TROJAN -> {
                writer.write("    password: \"${node.password}\"\n")
                writeTlsAndTransportStr(node, supportsAntiDpi, params, writer)
            }
            ProxyType.HYSTERIA2 -> {
                writer.write("    password: \"${node.password}\"\n")
                if (node.sni.isNotBlank()) writer.write("    sni: \"${node.sni}\"\n")
                if (node.skipCertVerify) writer.write("    skip-cert-verify: true\n")
            }
            else -> {}
        }
    }

    private fun writeTlsAndTransportStr(node: ProxyNode, supportsAntiDpi: Boolean, params: Map<String, String>, writer: java.io.Writer) {
        // 1. СЕТЬ (Transport)
        if (node.network != "tcp") {
            writer.write("    network: ${node.network}\n")
            when (node.network) {
                "ws" -> writer.write("    ws-opts:\n      path: \"${node.wsPath}\"\n")
                "grpc" -> {
                    writer.write("    grpc-opts:\n")
                    // ВАЖНО: Только grpc-service-name, как в официальной документации Mihomo
                    writer.write("      grpc-service-name: \"${node.grpcServiceName}\"\n")
                }
            }
        }

        // 2. TLS, ALPN и REALITY
        if (node.tls) {
            writer.write("    tls: true\n")
            if (node.sni.isNotBlank()) writer.write("    servername: \"${node.sni}\"\n")

            // Для gRPC обязателен h2
            if (node.network == "grpc") {
                writer.write("    alpn: [h2]\n")
            } else if (node.alpn.isNotEmpty()) {
                writer.write("    alpn: [${node.alpn.joinToString(", ")}]\n")
            }

            val finalFingerprint = if (node.fingerprint.isBlank() || node.fingerprint == "null") "chrome" else node.fingerprint
            writer.write("    client-fingerprint: $finalFingerprint\n")

            if (node.isReality) {
                writer.write("    reality-opts:\n      public-key: \"${node.realityPubKey}\"\n")
                if (!node.realityShortId.isNullOrBlank() && node.realityShortId != "null") {
                    writer.write("      short-id: \"${node.realityShortId}\"\n")
                }
            }
        }

        // 3. ФРАГМЕНТАЦИЯ (Отключаем для gRPC, так как она его ломает)
        if (node.network != "grpc" && supportsAntiDpi && params["Fragment"] == "1" && (node.tls || node.network == "tcp")) {
            val fPackets = params["FragPack"] ?: "tlshello"
            val fLen = params["FragLen"] ?: "100-200"
            val fInt = params["FragInt"] ?: "10-20"
            writer.write("    fragment:\n      enabled: true\n      packets: $fPackets\n      length: $fLen\n      interval: $fInt\n")
        }
    }
}