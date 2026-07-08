package com.msp1974.vacompanion.sendspin

import com.sendspin.protocol.ClientState

/** Single source of truth for Sendspin default values, shared between config properties,
 * their SharedPreferences-backed persistence and [SendspinUtils]. */
object SendspinDefaults {
    const val PORT = 8928
    const val PATH = "/sendspin"
}

/** SharedPreferences keys for Sendspin settings. These are also reused as the JSON field
 * names in the settings payload pushed from Home Assistant (see APPConfig.processSettings),
 * since both are intentionally kept in sync. */
object SendspinPrefKeys {
    const val ENABLED = "sendspin_enabled"
    const val HOST = "sendspin_host"
    const val PORT = "sendspin_port"
    const val PATH = "sendspin_path"
    const val RECONNECT = "sendspin_reconnect"
    const val STATIC_DELAY_MS = "sendspin_static_delay_ms"
}

enum class SendspinConnectionMode {
    CLIENT_INITIATED,
}

data class SendspinRuntimeStatus(
    val enabled: Boolean = false,
    val running: Boolean = false,
    val connected: Boolean = false,
    val endpoint: String = "",
    val state: ClientState = ClientState.IDLE,
    val serverName: String = "",
    val serverId: String = "",
    val lastError: String = "",
) {
    val summary: String
        get() = when {
            !enabled -> "Disabled"
            !running -> "Stopped"
            connected && serverName.isNotBlank() -> "Connected to $serverName"
            connected -> "Connected"
            lastError.isNotBlank() -> "Error: $lastError"
            else -> state.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
}
