package com.msp1974.vacompanion.sendspin

import com.sendspin.protocol.ClientState

internal object SendspinUtils {
    fun normalizePath(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return SendspinDefaults.PATH
        return if (trimmed.startsWith("/")) trimmed else "/$trimmed"
    }

    fun buildEndpoint(host: String, port: Int, path: String): String {
        val normalizedHost = host.ifBlank { "127.0.0.1" }
        val normalizedPort = port.coerceIn(1, 65535)
        val normalizedPath = normalizePath(path)
        return "ws://$normalizedHost:$normalizedPort$normalizedPath"
    }

    fun isConnectedState(state: ClientState): Boolean {
        return state == ClientState.CLOCK_SYNCING || state == ClientState.STREAMING
    }
}
