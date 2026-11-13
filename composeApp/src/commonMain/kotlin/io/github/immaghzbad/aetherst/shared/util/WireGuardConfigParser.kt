package io.github.immaghzbad.aetherst.shared.util

import io.github.immaghzbad.aetherst.shared.model.CustomWireGuardConfig

object WireGuardConfigParser {

    /**
     * Parses a standard WireGuard INI-style configuration file (.conf)
     * e.g. Proton VPN, Mullvad, IVPN, or standard wg-quick configurations.
     */
    fun parse(rawText: String): CustomWireGuardConfig {
        var privateKey = ""
        val addressV4List = mutableListOf<String>()
        val addressV6List = mutableListOf<String>()
        val dnsList = mutableListOf<String>()
        var mtu = 1280

        var publicKey = ""
        var endpoint = ""
        val allowedIpsV4List = mutableListOf<String>()
        val allowedIpsV6List = mutableListOf<String>()
        var keepalive = 25

        var currentSection = ""

        val lines = rawText.lines()
        for (rawLine in lines) {
            val line = rawLine.substringBefore('#').substringBefore(';').trim()
            if (line.isEmpty()) continue

            if (line.startsWith("[") && line.endsWith("]")) {
                currentSection = line.substring(1, line.length - 1).trim().lowercase()
                continue
            }

            val eqIdx = line.indexOf('=')
            if (eqIdx == -1) continue

            val key = line.substring(0, eqIdx).trim().lowercase()
            val value = line.substring(eqIdx + 1).trim()

            when (currentSection) {
                "interface" -> {
                    when (key) {
                        "privatekey" -> privateKey = value
                        "address" -> {
                            val parts = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                            for (p in parts) {
                                if (isIpv6Address(p)) {
                                    addressV6List.add(p)
                                } else {
                                    addressV4List.add(p)
                                }
                            }
                        }
                        "dns" -> {
                            val parts = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                            dnsList.addAll(parts)
                        }
                        "mtu" -> {
                            mtu = value.toIntOrNull()?.coerceIn(576, 1500) ?: 1280
                        }
                    }
                }
                "peer" -> {
                    when (key) {
                        "publickey" -> publicKey = value
                        "endpoint" -> endpoint = value
                        "allowedips" -> {
                            val parts = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                            for (p in parts) {
                                if (isIpv6Address(p)) {
                                    allowedIpsV6List.add(p)
                                } else {
                                    allowedIpsV4List.add(p)
                                }
                            }
                        }
                        "persistentkeepalive" -> {
                            keepalive = value.toIntOrNull()?.coerceAtLeast(0) ?: 25
                        }
                    }
                }
            }
        }

        return CustomWireGuardConfig(
            rawConfig = rawText,
            privateKey = privateKey,
            addressV4 = addressV4List.joinToString(", "),
            addressV6 = addressV6List.joinToString(", "),
            dns = if (dnsList.isNotEmpty()) dnsList.joinToString(", ") else "1.1.1.1",
            mtu = if (mtu in 1120..1420) mtu else 1280,
            publicKey = publicKey,
            endpoint = endpoint,
            allowedIpsV4 = if (allowedIpsV4List.isNotEmpty()) allowedIpsV4List.joinToString(", ") else "0.0.0.0/0",
            allowedIpsV6 = if (allowedIpsV6List.isNotEmpty()) allowedIpsV6List.joinToString(", ") else "::/0",
            persistentKeepalive = keepalive
        )
    }

    /**
     * Determines whether an address or CIDR string represents an IPv6 address.
     */
    fun isIpv6Address(address: String): Boolean {
        val pure = address.substringBefore('/')
        return pure.contains(':')
    }

    /**
     * Filters IPv6 addresses and routes if the primary tunnel does not support IPv6,
     * preventing routing loops or gateway errors.
     */
    fun filterForPrimarySupport(config: CustomWireGuardConfig, supportsIpv6: Boolean): CustomWireGuardConfig {
        return if (!supportsIpv6) {
            config.copy(
                addressV6 = "",
                allowedIpsV6 = ""
            )
        } else {
            config
        }
    }
}

