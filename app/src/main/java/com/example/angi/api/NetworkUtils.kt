package com.example.angi.api

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    /**
     * Injectable IP detector for testing and runtime resolution.
     */
    var ipDetector: () -> String? = ::findLocalIpv4Address

    fun getLocalIpv4Address(): String? = ipDetector()

    private fun findLocalIpv4Address(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            var fallbackIp: String? = null
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val addrs = intf.inetAddresses ?: continue
                for (addr in addrs) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        val host = addr.hostAddress
                        if (!host.isNullOrBlank() && host != "0.0.0.0" && host != "127.0.0.1") {
                            val name = intf.name.lowercase()
                            if (name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("en") || name.startsWith("ap")) {
                                return host
                            }
                            if (fallbackIp == null) {
                                fallbackIp = host
                            }
                        }
                    }
                }
            }
            return fallbackIp
        } catch (_: Throwable) {
            return null
        }
    }
}
