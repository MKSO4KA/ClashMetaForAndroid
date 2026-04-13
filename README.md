# Clash Meta for Android (Enhanced Edition)

This version is a specialized fork of Clash Meta for Android, focused on **Advanced Mimicry**, **Automated Subscription Management**, and **DPI Circumvention**. It transforms the client from a simple proxy-handler into a powerful automated network tool.

---

## 🆕 Key Features

### 🎭 Advanced Mimicry & Device Spoofing
Bypass ISP or Provider-level restrictions by masquerading as other popular clients.
*   **Preset Device Profiles:** Instant spoofing for `v2rayTun (Android)`, `Happ (Android)`, and `Happ (Windows PC)`.
*   **Custom Header Injection:** Manually configure `User-Agent`, `x-hwid`, `x-device-model`, and `App-Version` to match any device fingerprint.
*   **Dynamic Spoofing:** Parameters can be passed directly through subscription URLs (via `#fragment`) for on-the-fly header rotation.

### 🔄 Integrated Universal SubConverter (MegaConverter)
No more reliance on insecure third-party web converters.
*   **Raw Link Support:** Direct import of `vless://`, `vmess://`, `trojan://`, `ss://`, `ssr://`, `hysteria2://`, and `tuic://`.
*   **V2Ray JSON Array Support:** Full parsing of native V2Ray configuration formats.
*   **Smart Node Classification:** 
    *   **Overseas-Auto:** Automatic grouping of international nodes with latency testing.
    *   **RU/RB-Auto:** Deterministic identification of Russian and Belarusian nodes using regex and geolocation markers.
    *   **Trash Filter:** Automatic blacklisting of nodes containing keywords like *maintenance, dead, error, or expired*.

### 📡 Radar Mode (Header Sniffer)
Intercept and clone device fingerprints from other apps.
*   By running a local capture server, you can "sniff" the exact headers sent by apps like v2rayNG or Kitsunebi and apply them to your Clash profiles for perfect mimicry.

### 🛡 Anti-DPI & Protocol Hardening
*   **Native Fragmentation:** Built-in support for TLS Hello fragmentation to bypass SNI-based filtering.
*   **Mux/H2Mux:** Streamlined multiplexing support to reduce handshake overhead and improve stability on high-latency connections.

---

## ⚡ Comparison: Enhanced vs. Standard

| Feature | Standard Meta | Enhanced Edition |
| :--- | :--- | :--- |
| **Input Format** | YAML Only | All raw URIs (VLESS, VMESS, etc.) |
| **Fingerprinting** | Static User-Agent | Full Device Mimicry (Spoofing) |
| **Node Management** | Manual Grouping | Intelligent Auto-Sorting (RU/RB/Overseas) |
| **DPI Protection** | Limited/Config-based | Built-in Fragment & Mux Automation |
| **GUI Control** | Basic Proxy View | Advanced Node Blacklisting & Profile Editor |

---

## 🛠 Technical Improvements
*   **Streaming YAML Engine:** Optimized `YamlBuilder` uses Streaming IO to generate large configurations with a near-zero memory footprint.
*   **Enhanced Sub-Info Parsing:** Precise tracking of traffic usage (`subscription-userinfo`) using `BigDecimal` for multi-format compatibility.
*   **Zero-Allocation Regex:** Optimized node filtering using pre-compiled patterns for faster subscription updates.

---

## 🗺 Roadmap: Future Innovations
*   **Mesh-Style Offline Config Sharing:** We are working on a revolutionary feature to share working proxy profiles and configurations **without an internet connection**. 
*   By leveraging **Google Nearby** and **Wi-Fi Direct**, users will be able to form a localized "Mesh" to distribute censorship-resistant configurations in environments where external repositories are fully blocked.

---

## Credits & Technologies
*   **Core:** Clash Meta
*   **UI:** Material Design 3
*   **Networking:** OkHttp3, Google Nearby API