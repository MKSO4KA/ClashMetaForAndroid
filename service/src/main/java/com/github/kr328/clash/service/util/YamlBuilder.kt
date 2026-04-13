package com.github.kr328.clash.service.util

object YamlBuilder {
    fun buildYaml(proxies: List<ProxyNode>, pingUrl: String, pingInterval: String, pingTolerance: String): String {
        DecodeUtils.dLog("YamlBuilder: Start building YAML for ${proxies.size} proxies")
        val sb = StringBuilder()
        val uniqueProxies = deduplicateNames(proxies)

        sb.append("proxies:\n")
        for (node in uniqueProxies) {
            sb.append(buildProxyBlock(node))
        }

        sb.append("\nproxy-groups:\n")
        val hasTrash = uniqueProxies.any { it.isTrash }
        val overseas = uniqueProxies.filter { !it.isTrash }

        sb.append("- name: PROXY\n  type: select\n  proxies:\n    - Overseas-Auto\n")
        if (hasTrash) sb.append("    - RU/RB-Auto\n")
        sb.append("    - Manual-Select\n")

        sb.append("\n- name: Overseas-Auto\n  type: url-test\n  url: $pingUrl\n  interval: $pingInterval\n  tolerance: $pingTolerance\n  proxies:\n")
        val targetList = if (overseas.isEmpty()) uniqueProxies else overseas
        targetList.forEach { sb.append("    - \"${it.name}\"\n") }

        if (hasTrash) {
            sb.append("\n- name: RU/RB-Auto\n  type: url-test\n  url: $pingUrl\n  interval: $pingInterval\n  tolerance: $pingTolerance\n  proxies:\n")
            uniqueProxies.filter { it.isTrash }.forEach { sb.append("    - \"${it.name}\"\n") }
        }

        sb.append("\n- name: Manual-Select\n  type: select\n  proxies:\n")
        uniqueProxies.forEach { sb.append("    - \"${it.name}\"\n") }

        sb.append("\n\nrules:\n  - DOMAIN,rezvorck.github.io,REJECT\n  - DOMAIN,tigr1234566.github.io,REJECT\n  - MATCH,PROXY\n")

        return sb.toString()
    }

    private fun deduplicateNames(proxies: List<ProxyNode>): List<ProxyNode> {
        val nameSet = mutableSetOf<String>()
        val result = mutableListOf<ProxyNode>()
        for (node in proxies) {
            var safeName = node.name.replace("\"", "\\\"").replace("\n", "").trim()
            if (safeName.isBlank()) safeName = "Unknown-Proxy"
            var finalName = safeName
            var counter = 1
            while (nameSet.contains(finalName)) {
                finalName = "$safeName $counter"
                counter++
            }
            nameSet.add(finalName)
            node.name = finalName
            result.add(node)
        }
        return result
    }

    private fun buildProxyBlock(node: ProxyNode): String {
        val sb = StringBuilder()
        sb.append("  - name: \"${node.name}\"\n    server: \"${node.server}\"\n    port: ${node.port}\n    type: ${node.type.name.lowercase()}\n")
        if (node.udp) sb.append("    udp: true\n")

        when (node.type) {
            ProxyType.SHADOWSOCKS -> {
                sb.append("    cipher: \"${node.cipher}\"\n    password: \"${node.password}\"\n")
                if (node.plugin.isNotBlank()) {
                    sb.append("    plugin: ${node.plugin}\n    plugin-opts:\n")
                    node.pluginOpts.forEach { (k, v) -> sb.append("      $k: \"$v\"\n") }
                    if (node.obfs.isNotBlank()) {
                        sb.append("      mode: ${node.obfs}\n      host: \"${node.obfsParam}\"\n")
                    }
                }
            }
            ProxyType.SSR -> {
                sb.append("    cipher: \"${node.cipher}\"\n    password: \"${node.password}\"\n    protocol: ${node.protocol}\n    protocol-param: \"${node.protocolParam}\"\n    obfs: ${node.obfs}\n    obfs-param: \"${node.obfsParam}\"\n")
            }
            ProxyType.VLESS -> {
                sb.append("    uuid: \"${node.uuid}\"\n")
                if (node.flow.isNotBlank()) sb.append("    flow: ${node.flow}\n")
                appendTlsAndTransport(node, sb)
            }
            ProxyType.VMESS -> {
                sb.append("    uuid: \"${node.uuid}\"\n    alterId: ${node.alterId}\n    cipher: \"${node.cipher}\"\n")
                appendTlsAndTransport(node, sb)
            }
            ProxyType.TROJAN -> {
                sb.append("    password: \"${node.password}\"\n")
                appendTlsAndTransport(node, sb)
            }
            ProxyType.HYSTERIA2 -> {
                sb.append("    password: \"${node.password}\"\n")
                if (node.sni.isNotBlank()) sb.append("    sni: \"${node.sni}\"\n")
                if (node.alpn.isNotEmpty()) {
                    sb.append("    alpn:\n")
                    node.alpn.forEach { sb.append("      - $it\n") }
                }
                if (node.obfs.isNotBlank()) sb.append("    obfs: ${node.obfs}\n    obfs-password: \"${node.obfsPassword}\"\n")
                if (node.skipCertVerify) sb.append("    skip-cert-verify: true\n")
            }
            ProxyType.TUIC -> {
                sb.append("    uuid: \"${node.uuid}\"\n    password: \"${node.password}\"\n")
                if (node.sni.isNotBlank()) sb.append("    sni: \"${node.sni}\"\n")
                if (node.congestionController.isNotBlank()) sb.append("    congestion-controller: ${node.congestionController}\n")
                if (node.udpRelayMode.isNotBlank()) sb.append("    udp-relay-mode: ${node.udpRelayMode}\n")
                if (node.skipCertVerify) sb.append("    skip-cert-verify: true\n")
                if (node.alpn.isNotEmpty()) {
                    sb.append("    alpn:\n")
                    node.alpn.forEach { sb.append("      - $it\n") }
                }
            }
            else -> {}
        }
        return sb.toString()
    }

    private fun appendTlsAndTransport(node: ProxyNode, sb: StringBuilder) {
        if (node.network != "tcp") {
            sb.append("    network: ${node.network}\n")
            when (node.network) {
                "ws" -> {
                    sb.append("    ws-opts:\n      path: \"${node.wsPath}\"\n")
                    if (node.wsHeaders.isNotEmpty()) {
                        sb.append("      headers:\n")
                        node.wsHeaders.forEach { (k, v) -> sb.append("        $k: \"$v\"\n") }
                    }
                }
                "grpc" -> sb.append("    grpc-opts:\n      grpc-service-name: \"${node.grpcServiceName}\"\n")
                "xhttp", "httpupgrade" -> sb.append("    xhttp-opts:\n      path: \"${node.xhttpPath}\"\n      mode: \"${node.xhttpMode}\"\n")
            }
        }

        if (node.tls) {
            sb.append("    tls: true\n")
            if (node.sni.isNotBlank()) sb.append("    servername: \"${node.sni}\"\n")
            if (node.fingerprint.isNotBlank()) sb.append("    client-fingerprint: ${node.fingerprint}\n")
            if (node.skipCertVerify) sb.append("    skip-cert-verify: true\n")
            if (node.alpn.isNotEmpty()) {
                sb.append("    alpn:\n")
                node.alpn.forEach { sb.append("      - $it\n") }
            }
            if (node.isReality) {
                sb.append("    reality-opts:\n      public-key: \"${node.realityPubKey}\"\n")
                if (node.realityShortId.isNotBlank()) sb.append("      short-id: \"${node.realityShortId}\"\n")
            }
        }
    }
}