package com.github.kr328.clash.service.util

object YamlBuilder {
    fun buildYaml(
        proxies: List<ProxyNode>,
        params: Map<String, String>
    ): String = buildString {
        DecodeUtils.dLog("YamlBuilder: Start building YAML for ${proxies.size} proxies")
        val uniqueProxies = deduplicateNames(proxies)

        val blacklistSet = (params["Blacklist"] ?: "").split(",").map { it.trim() }.toSet()
        val whitelistSet = (params["Whitelist"] ?: "").split(",").map { it.trim() }.toSet()

        // 1. Присваиваем статус каждому прокси
        uniqueProxies.forEach { node ->
            node.isTrash = DecodeUtils.isTrash(node.name)
        }

        // 2. Распределяем по корзинам
        val activeProxies = uniqueProxies.filter { node ->
            // Прокси активен, если его нет в ручном бане И (он не мусор ИЛИ он спасен в вайтлисте)
            !blacklistSet.contains(node.name) && (!node.isTrash || whitelistSet.contains(node.name))
        }

        val blacklistedProxies = uniqueProxies.filter { node ->
            // Прокси в корзине, если он в ручном бане ИЛИ (он мусор И не спасен в вайтлисте)
            blacklistSet.contains(node.name) || (node.isTrash && !whitelistSet.contains(node.name))
        }

        val ruRbProxies = activeProxies.filter { DecodeUtils.isRuRb(it.name) }
        val overseasProxies = activeProxies.filter { !DecodeUtils.isRuRb(it.name) }

        val hasRuRb = ruRbProxies.isNotEmpty()

        // БЕРЕМ НАСТРОЙКИ ИЗ ИНТЕРФЕЙСА (БЕЗ ХАРДКОДА)
        val pingUrl = params["PingUrl"] ?: "http://cp.cloudflare.com/generate_204"
        val pingInt = params["Ping"] ?: "300"
        val pingTol = params["Tolerance"] ?: "150"

        // Пишем сами прокси
        append("proxies:\n")
        uniqueProxies.forEach { node ->
            append(buildProxyBlock(node, params))
        }

        append("\nproxy-groups:\n")

        // --- Группа 1: Главный PROXY ---
        append("- name: PROXY\n  type: select\n  proxies:\n")
        if (activeProxies.isNotEmpty()) {
            append("    - Overseas-Auto\n")
            if (hasRuRb) append("    - RU/RB-Auto\n")
            append("    - Manual-Select\n")
        } else {
            append("    - DIRECT\n")
        }

        // --- Группа 2: Забугорные (Overseas) ---
        append("\n- name: Overseas-Auto\n  type: url-test\n  url: \"$pingUrl\"\n  interval: $pingInt\n  tolerance: $pingTol\n  proxies:\n")
        val targetOverseas = if (overseasProxies.isEmpty()) activeProxies else overseasProxies
        if (targetOverseas.isNotEmpty()) {
            targetOverseas.forEach { append("    - \"${it.name}\"\n") }
        } else {
            append("    - DIRECT\n")
        }

        // --- Группа 3: СНГ (RU/RB) ---
        if (hasRuRb) {
            // Используем ТОТ ЖЕ pingUrl, который задал пользователь!
            append("\n- name: RU/RB-Auto\n  type: url-test\n  url: \"$pingUrl\"\n  interval: $pingInt\n  tolerance: $pingTol\n  proxies:\n")
            ruRbProxies.forEach { append("    - \"${it.name}\"\n") }
        }

        // --- Группа 4: Ручной выбор ---
        append("\n- name: Manual-Select\n  type: select\n  proxies:\n")
        if (activeProxies.isNotEmpty()) {
            activeProxies.forEach { append("    - \"${it.name}\"\n") }
        } else {
            append("    - DIRECT\n")
        }

        // --- Группа 5: КОРЗИНА (Blacklisted) ---
        append("\n- name: Blacklisted\n  type: select\n  proxies:\n")
        if (blacklistedProxies.isNotEmpty()) {
            blacklistedProxies.forEach { append("    - \"${it.name}\"\n") }
        } else {
            append("    - DIRECT\n")
        }

        // Базовые правила маршрутизации
        append("\n\nrules:\n  - DOMAIN,rezvorck.github.io,REJECT\n  - DOMAIN,tigr1234566.github.io,REJECT\n  - MATCH,PROXY\n")
    }

    private fun buildProxyBlock(node: ProxyNode, params: Map<String, String>): String = buildString {
        append("  - name: \"${node.name}\"\n    server: \"${node.server}\"\n    port: ${node.port}\n    type: ${node.type.name.lowercase()}\n")
        if (node.udp) append("    udp: true\n")

        val supportsAntiDpi = node.type == ProxyType.VLESS || node.type == ProxyType.VMESS || node.type == ProxyType.TROJAN

        // Внедрение настроек MUX
        if (supportsAntiDpi && params["Mux"] == "1") {
            val maxCon = params["MuxCon"] ?: "8"
            val minStr = params["MuxMin"] ?: "4"
            append("    smux:\n      enabled: true\n      protocol: h2mux\n      max-connections: $maxCon\n      min-streams: $minStr\n")
        }

        when (node.type) {
            ProxyType.SHADOWSOCKS -> {
                append("    cipher: \"${node.cipher}\"\n    password: \"${node.password}\"\n")
                if (node.plugin.isNotBlank()) {
                    append("    plugin: ${node.plugin}\n    plugin-opts:\n")
                    node.pluginOpts.forEach { (k, v) -> append("      $k: \"$v\"\n") }
                    if (node.obfs.isNotBlank()) append("      mode: ${node.obfs}\n      host: \"${node.obfsParam}\"\n")
                }
            }
            ProxyType.VLESS -> {
                append("    uuid: \"${node.uuid}\"\n")
                if (node.flow.isNotBlank()) append("    flow: ${node.flow}\n")
                append(getTlsAndTransportStr(node, supportsAntiDpi, params))
            }
            ProxyType.VMESS -> {
                append("    uuid: \"${node.uuid}\"\n    alterId: ${node.alterId}\n    cipher: \"${node.cipher}\"\n")
                append(getTlsAndTransportStr(node, supportsAntiDpi, params))
            }
            ProxyType.TROJAN -> {
                append("    password: \"${node.password}\"\n")
                append(getTlsAndTransportStr(node, supportsAntiDpi, params))
            }
            ProxyType.HYSTERIA2 -> {
                append("    password: \"${node.password}\"\n")
                if (node.sni.isNotBlank()) append("    sni: \"${node.sni}\"\n")
                if (node.skipCertVerify) append("    skip-cert-verify: true\n")
            }
            else -> {}
        }
    }

    private fun getTlsAndTransportStr(node: ProxyNode, supportsAntiDpi: Boolean, params: Map<String, String>): String = buildString {
        if (node.network != "tcp") {
            append("    network: ${node.network}\n")
            when (node.network) {
                "ws" -> {
                    append("    ws-opts:\n      path: \"${node.wsPath}\"\n")
                    if (node.wsHeaders.isNotEmpty()) {
                        append("      headers:\n")
                        node.wsHeaders.forEach { (k, v) -> append("        $k: \"$v\"\n") }
                    }
                }
                "grpc" -> append("    grpc-opts:\n      grpc-service-name: \"${node.grpcServiceName}\"\n")
                "xhttp", "httpupgrade" -> append("    xhttp-opts:\n      path: \"${node.xhttpPath}\"\n      mode: \"${node.xhttpMode}\"\n")
            }
        }

        if (node.tls) {
            append("    tls: true\n")
            if (node.sni.isNotBlank()) append("    servername: \"${node.sni}\"\n")
            if (node.fingerprint.isNotBlank()) append("    client-fingerprint: ${node.fingerprint}\n")
            if (node.skipCertVerify) append("    skip-cert-verify: true\n")
            if (node.isReality) {
                append("    reality-opts:\n      public-key: \"${node.realityPubKey}\"\n")
                if (node.realityShortId.isNotBlank()) append("      short-id: \"${node.realityShortId}\"\n")
            }
        }

        // Внедрение настроек FRAGMENT
        if (supportsAntiDpi && params["Fragment"] == "1" && (node.tls || node.network == "tcp")) {
            val fPackets = params["FragPack"] ?: "tlshello"
            val fLen = params["FragLen"] ?: "100-200"
            val fInt = params["FragInt"] ?: "10-20"
            append("    client-fingerprint: chrome\n") // Обязательно для работы Fragment
            append("    fragment:\n      enabled: true\n      packets: $fPackets\n      length: $fLen\n      interval: $fInt\n")
        }
    }

    private fun deduplicateNames(proxies: List<ProxyNode>): List<ProxyNode> {
        val nameSet = mutableSetOf<String>()
        return proxies.map { node ->
            var safeName = node.name.replace("\"", "\\\"").replace("\n", "").trim()
            if (safeName.isBlank()) safeName = "Unknown-Proxy"
            var finalName = safeName
            var counter = 1
            while (!nameSet.add(finalName)) {
                finalName = "$safeName $counter"
                counter++
            }
            node.name = finalName
            node
        }
    }
}