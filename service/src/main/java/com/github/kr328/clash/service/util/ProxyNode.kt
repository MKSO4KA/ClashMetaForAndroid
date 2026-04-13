package com.github.kr328.clash.service.util

enum class ProxyType {
    VLESS, VMESS, TROJAN, SHADOWSOCKS, SSR, HYSTERIA, HYSTERIA2, TUIC, WIREGUARD, UNKNOWN
}

data class ProxyNode(
    var name: String = "",
    var type: ProxyType = ProxyType.UNKNOWN,
    var server: String = "",
    var port: Int = 443,

    var uuid: String = "",
    var password: String = "",
    var cipher: String = "auto",
    var alterId: Int = 0,

    var security: String = "none",
    var tls: Boolean = false,
    var sni: String = "",
    var alpn: List<String> = emptyList(),
    var fingerprint: String = "",
    var skipCertVerify: Boolean = false,
    var isReality: Boolean = false,
    var realityPubKey: String = "",
    var realityShortId: String = "",

    var network: String = "tcp",
    var wsPath: String = "/",
    var wsHeaders: Map<String, String> = emptyMap(),
    var grpcServiceName: String = "",
    var grpcAuthority: String = "",
    var xhttpPath: String = "/",
    var xhttpMode: String = "auto",

    var flow: String = "",

    var plugin: String = "",
    var pluginOpts: Map<String, String> = emptyMap(),
    var obfs: String = "",
    var obfsParam: String = "",
    var protocol: String = "",
    var protocolParam: String = "",

    var upMbps: String = "",
    var downMbps: String = "",
    var ports: String = "",
    var obfsPassword: String = "",
    var congestionController: String = "",
    var udpRelayMode: String = "",

    var udp: Boolean = true,
    var isTrash: Boolean = false
)