package com.msp1974.vacompanion.sendspin

import android.content.Context
import android.os.Build
import com.msp1974.vacompanion.device.DeviceManager
import com.msp1974.vacompanion.utils.Event
import com.sendspin.protocol.AudioFormat
import com.sendspin.protocol.ArtworkChannel
import com.sendspin.protocol.ClientAdvertiser
import com.sendspin.protocol.ClientPreferences
import com.sendspin.protocol.ClientState
import com.sendspin.protocol.DiscoveryService
import com.sendspin.protocol.JsonOptionalAdapterFactory
import com.sendspin.protocol.SendSpinClient
import com.sendspin.protocol.SendSpinServerHost
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import timber.log.Timber
import com.msp1974.vacompanion.utils.Helpers

internal class SendspinControllerImpl(
    private val context: Context,
    private val deviceManager: DeviceManager,
) : SendspinController {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private val config get() = deviceManager.config
    private val deviceInfo get() = deviceManager.deviceInfo

    private val moshi: Moshi = Moshi.Builder()
        .add(JsonOptionalAdapterFactory())
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val okHttpClient = OkHttpClient.Builder().build()

    private var sendspinClient: SendSpinClient? = null
    private var stateJob: Job? = null
    private var serverNameJob: Job? = null
    private var serverIdJob: Job? = null
    private var discoveryJob: Job? = null
    private var advertiseJob: Job? = null
    private var serverHost: SendSpinServerHost? = null

    private var wasMusicPlayingBeforeSendspin = false
    private var isDuckedForVoiceInteraction = false
    private var activeDuckingGain = 0.25f

    override var status: SendspinRuntimeStatus = SendspinRuntimeStatus(enabled = config.sendspinEnabled)
        private set

    override fun start() {
        if (!config.sendspinEnabled) {
            status = status.copy(enabled = false, running = false, connected = false)
            publishStatus()
            return
        }

        stopInternal(clearEnabled = false)

        val endpoint = when (config.sendspinConnectionMode) {
            SendspinConnectionMode.CLIENT_INITIATED -> buildEndpoint(config.sendspinHost)
            SendspinConnectionMode.SERVER_INITIATED -> buildEndpoint(Helpers.getIpv4HostAddress().ifBlank { "0.0.0.0" })
        }
        val client = createClient()
        sendspinClient = client
        status = status.copy(
            enabled = true,
            running = true,
            connected = false,
            endpoint = endpoint,
            state = ClientState.CONNECTING,
            lastError = "",
        )
        publishStatus()

        client.setStaticDelayMs(config.sendspinStaticDelayMs.coerceIn(0, 5000))

        stateJob = client.state
            .onEach { state ->
                onClientStateChanged(state)
            }
            .launchIn(scope)

        serverNameJob = client.serverName
            .onEach { serverName ->
                status = status.copy(serverName = serverName)
                publishStatus()
            }
            .launchIn(scope)

        serverIdJob = client.serverId
            .onEach { serverId ->
                status = status.copy(serverId = serverId)
                publishStatus()
            }
            .launchIn(scope)

        when (config.sendspinConnectionMode) {
            SendspinConnectionMode.CLIENT_INITIATED -> {
                if (config.sendspinHost.isBlank()) {
                    startDiscoveryAndConnect(client)
                } else {
                    client.connect(endpoint)
                }
            }
            SendspinConnectionMode.SERVER_INITIATED -> {
                startServerHost(client)
            }
        }
    }

    override fun stop() {
        stopInternal(clearEnabled = true)
    }

    override fun onNetworkAvailable() {
        if (config.sendspinEnabled && sendspinClient == null) {
            start()
        }
    }

    override fun onNetworkLost() {
        sendspinClient?.disconnect("network_lost")
        status = status.copy(connected = false)
        publishStatus()
        restoreMusicIfNeeded()
    }

    override fun setDucked(ducked: Boolean) {
        val duckingGain = (config.duckingVolume / 50f).coerceIn(0f, 1f)
        if (isDuckedForVoiceInteraction == ducked && (!ducked || activeDuckingGain == duckingGain)) return
        isDuckedForVoiceInteraction = ducked
        activeDuckingGain = duckingGain
        Timber.d("Sendspin ducking set: active=$ducked gain=$duckingGain")
        AndroidSendspinAudioPlayer.setDuckingEnabled(ducked, duckingGain)
    }

    private fun startDiscoveryAndConnect(client: SendSpinClient) {
        discoveryJob?.cancel()
        val browser = AndroidSendspinNsdBrowser(context)
        val discovery = DiscoveryService(browser)
        discoveryJob = scope.launch {
            discovery.discover().collect { servers ->
                val first = servers.firstOrNull() ?: return@collect
                val url = buildEndpoint(first.host, first.port)
                if (status.endpoint != url || status.state == ClientState.IDLE || status.state == ClientState.DISCONNECTED) {
                    status = status.copy(endpoint = url)
                    publishStatus()
                    client.connect(url)
                }
            }
        }
    }

    private fun createClient(): SendSpinClient {
        val preferences = ClientPreferences(
            // Listed in priority order (first preferred): prefer Opus for bandwidth efficiency,
            // falling back to PCM. Only list codecs AndroidSendspinAudioPlayer can actually decode.
            supportedFormats = listOf(
                AudioFormat(codec = "opus", channels = 2, sampleRate = 48_000, bitDepth = 16),
                AudioFormat(codec = "opus", channels = 1, sampleRate = 48_000, bitDepth = 16),
                AudioFormat(codec = "pcm", channels = 2, sampleRate = 48_000, bitDepth = 16),
                AudioFormat(codec = "pcm", channels = 1, sampleRate = 48_000, bitDepth = 16),
            ),
            artworkChannels = listOf(
                ArtworkChannel(source = "album", format = "jpeg", mediaWidth = 512, mediaHeight = 512),
                ArtworkChannel(source = "artist", format = "jpeg", mediaWidth = 512, mediaHeight = 512),
            ),
        )

        return SendSpinClient(
            okHttpClient = okHttpClient,
            moshi = moshi,
            preferences = preferences,
            clientId = config.uuid,
            clientName = "VACA-${config.uuid.take(8)}",
            manufacturer = Build.MANUFACTURER ?: "Android",
            productName = Build.MODEL ?: "Device",
            softwareVersion = deviceInfo.software.appVersion,
            macAddress = "",
            audioPlayerFactory = { buffer, clock ->
                AndroidSendspinAudioPlayer(buffer, clock)
            },
            reconnectEnabled = config.sendspinReconnect,
        )
    }

    private fun startServerHost(client: SendSpinClient) {
        advertiseJob?.cancel()
        serverHost?.stopServer()
        val host = SendSpinServerHost(
            client = client,
            moshi = moshi,
            port = config.sendspinPort,
            scope = scope,
        )
        serverHost = host

        runCatching {
            host.startServer()
            val advertiser = ClientAdvertiser(AndroidSendspinNsdRegistrar(context))
            advertiseJob = advertiser.advertise(
                port = config.sendspinPort,
                name = "VACA-${config.uuid.take(8)}",
                manufacturer = Build.MANUFACTURER ?: "Android",
                model = Build.MODEL ?: "Device",
            ).onEach {
                Timber.d("Sendspin NSD advertisement active")
            }.launchIn(scope)
        }.onFailure { error ->
            Timber.e(error, "Failed to start Sendspin server host")
            status = status.copy(
                state = ClientState.ERROR,
                lastError = "Failed to start listener",
            )
            publishStatus()
        }
    }

    private fun onClientStateChanged(state: ClientState) {
        val connected = SendspinUtils.isConnectedState(state)
        status = status.copy(
            state = state,
            connected = connected,
            running = true,
            lastError = if (state == ClientState.ERROR) "Connection failed" else status.lastError,
        )
        publishStatus()

        if (connected) {
            config.eventBroadcaster.notifyEvent(Event("sendspinConnected", false, true))
            arbitrateMusicForSendspin()
        } else if (state == ClientState.DISCONNECTED || state == ClientState.ERROR) {
            config.eventBroadcaster.notifyEvent(Event("sendspinConnected", true, false))
            restoreMusicIfNeeded()
        }
    }

    private fun arbitrateMusicForSendspin() {
        val musicService = com.msp1974.vacompanion.players.MusicPlayerService.sInstance
        if (musicService != null && !wasMusicPlayingBeforeSendspin) {
            wasMusicPlayingBeforeSendspin = true
            musicService.pause()
            Timber.i("Paused local music player due to Sendspin stream")
        }
    }

    private fun restoreMusicIfNeeded() {
        if (wasMusicPlayingBeforeSendspin) {
            com.msp1974.vacompanion.players.MusicPlayerService.sInstance?.resume()
            wasMusicPlayingBeforeSendspin = false
        }
    }

    private fun stopInternal(clearEnabled: Boolean) {
        discoveryJob?.cancel()
        advertiseJob?.cancel()
        advertiseJob = null
        serverHost?.stopServer()
        serverHost = null
        stateJob?.cancel()
        serverNameJob?.cancel()
        serverIdJob?.cancel()

        sendspinClient?.disconnect("shutdown")
        sendspinClient = null

        restoreMusicIfNeeded()

        status = status.copy(
            enabled = if (clearEnabled) false else config.sendspinEnabled,
            running = false,
            connected = false,
            state = ClientState.IDLE,
            serverName = "",
            serverId = "",
            lastError = if (clearEnabled) "" else status.lastError,
        )
        publishStatus()
    }

    private fun publishStatus() {
        deviceManager.updateSendspinStatus(status)
    }

    private fun buildEndpoint(host: String, port: Int = config.sendspinPort): String {
        return SendspinUtils.buildEndpoint(host, port, config.sendspinPath)
    }

    fun destroy() {
        stopInternal(clearEnabled = false)
        scope.cancel()
        job.cancel()
    }
}
