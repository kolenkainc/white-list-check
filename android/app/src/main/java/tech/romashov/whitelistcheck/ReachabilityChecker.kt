package tech.romashov.whitelistcheck

import java.net.InetSocketAddress
import java.net.Socket

class ReachabilityChecker(private val timeoutMs: Int) {
    private val portsToTry = intArrayOf(443, 80, 22)

    fun isAnyPortOpen(host: String): Boolean {
        for (port in portsToTry) {
            if (tcpConnect(host, port)) return true
        }
        return false
    }

    private fun tcpConnect(host: String, port: Int): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
