package de.meshcore.pocketmonitor.tcp

import java.net.InetAddress
import java.net.UnknownHostException

internal class TcpEndpoint private constructor(val host: String, val port: Int, val address: InetAddress) {
    override fun toString() = if (':' in host) "[$host]:$port" else "$host:$port"

    companion object {
        const val DEFAULT_PORT = 5000

        fun hasValidAddress(host: String): Boolean = parseAddress(host.trim().removeSurrounding("[", "]")) != null

        fun hasValidPort(port: Int?) = port != null && port in 1..65535

        fun parse(host: String, port: Int?): TcpEndpoint? {
            if (port == null || !hasValidPort(port)) return null
            val numericHost = host.trim().removeSurrounding("[", "]")
            val address = parseAddress(numericHost) ?: return null
            return TcpEndpoint(numericHost, port, address)
        }

        private fun parseAddress(host: String): InetAddress? {
            if (':' in host) {
                // Only numeric IPv6 candidates can reach InetAddress.getByName.
                if (host.any { it !in "0123456789abcdefABCDEF:." }) return null
                return try {
                    InetAddress.getByName(host)
                } catch (_: UnknownHostException) {
                    null
                }
            }
            val parts = host.split('.')
            if (parts.size != 4) return null
            val bytes = parts.map { part ->
                if (part.length !in 1..3 || (part.length > 1 && part.startsWith('0')) ||
                    part.any { it !in '0'..'9' }
                ) return null
                val value = part.toInt()
                if (value > 255) return null
                value.toByte()
            }
            return InetAddress.getByAddress(bytes.toByteArray())
        }
    }
}
