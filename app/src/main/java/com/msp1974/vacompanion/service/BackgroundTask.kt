package com.msp1974.vacompanion.service

import android.content.Context
import com.msp1974.vacompanion.data.AvailableAlarms
import com.msp1974.vacompanion.data.AvailableWakeSounds
import com.msp1974.vacompanion.device.DeviceManager
import com.msp1974.vacompanion.device.sensors.NetworkStatus
import com.msp1974.vacompanion.sendspin.SendspinControllerImpl
import com.msp1974.vacompanion.wakeword.AvailableWakeWords
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.EventListener
import com.msp1974.vacompanion.wyoming.ServerState
import com.msp1974.vacompanion.wyoming.WyomingTCPServer
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import timber.log.Timber

internal class BackgroundTaskController (private val context: Context, val deviceManager: DeviceManager) : EventListener {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private var server: WyomingTCPServer? = null
    private var networkJob: Job? = null
    private var sendspinController: SendspinControllerImpl? = null
    private var voiceInteractionActive = false
    private var mediaInterruptionActive = false
    private val config = deviceManager.config


    fun start() {
        config.eventBroadcaster.addListener(this)

        server = object: WyomingTCPServer(context, deviceManager) {
            override fun onEvent(event: String, data: JsonObject) {
                Timber.d("BackgroundTask - Event: $event - $data")
            }
            override fun onState(state: ServerState, restartIfStopped: Boolean) {
                Timber.d("BackgroundTask - State: $state")
                when (state) {
                    ServerState.RUNNING -> {}
                    ServerState.STOPPED -> {
                        Timber.d("Wyoming server stopped. Restart: $restartIfStopped")
                        // Restart server
                        if (restartIfStopped) runServer()
                    }
                    ServerState.ERRORED -> {
                        restartOnError()
                    }
                    else -> {}
                }
            }
        }
        runServer()

        sendspinController = SendspinControllerImpl(context, deviceManager)
        if (config.sendspinEnabled) {
            sendspinController?.start()
        } else {
            deviceManager.updateSendspinStatus(deviceManager.status.value.sendspin.copy(enabled = false, running = false))
        }

        networkJob?.cancel()
        networkJob = scope.launch {
            deviceManager.networkStatus.collectLatest { networkInfo ->
                when (networkInfo.status) {
                    NetworkStatus.Available -> sendspinController?.onNetworkAvailable()
                    NetworkStatus.Unavailable -> sendspinController?.onNetworkLost()
                }
            }
        }

        Timber.d("Background task initialisation completed")
    }

    fun restartOnError() {
        server?.let {
            it.stopServer()
            start()
        }
    }

    fun runServer() {
        // TODO: Implement a recovery process
        try {
            if (server != null && server?.state == ServerState.STOPPED) {
                scope.launch {
                    config.availableWakeWords = AvailableWakeWords(context).get()
                    config.availableAlarms = AvailableAlarms(context, deviceManager).get()
                    config.availableWakeSounds = AvailableWakeSounds(context, deviceManager).get()
                    server?.startServer()
                }
            } else {
                Timber.d("Server not setup or already running")
            }
        } catch (e: Exception) {
            Timber.e("Error starting server: ${e.message.toString()}")
        }
    }

    fun serverWatchDog() {
        // TODO: Add restart watchdog
    }

    fun shutdown() {
        Timber.i("Shutting down")
        config.eventBroadcaster.removeListener(this)
        networkJob?.cancel()
        server?.stopServer()
        sendspinController?.destroy()
        sendspinController = null
        voiceInteractionActive = false
        mediaInterruptionActive = false
        scope.cancel()
    }

    private fun syncSendspinDucking() {
        sendspinController?.setDucked(voiceInteractionActive || mediaInterruptionActive)
    }

    override fun onEventTriggered(event: Event) {
        when (event.eventName) {
            "sendspinEnabled" -> {
                val enabled = event.newValue as? Boolean ?: return
                if (enabled) {
                    sendspinController?.start()
                } else {
                    sendspinController?.stop()
                }
            }
            "sendspinStaticDelayMs" -> {
                // Restart to ensure new client settings are applied consistently.
                if (config.sendspinEnabled) {
                    sendspinController?.start()
                }
            }
            "voiceInteractionActive" -> {
                val active = event.newValue as? Boolean ?: return
                voiceInteractionActive = active
                syncSendspinDucking()
            }
            "musicPlayerPlayingStatus" -> {
                val active = event.newValue as? Boolean ?: return
                mediaInterruptionActive = active
                syncSendspinDucking()
            }
            "mediaPlaybackActive" -> {
                val active = event.newValue as? Boolean ?: return
                mediaInterruptionActive = active
                syncSendspinDucking()
            }
            "duckingVolume" -> {
                syncSendspinDucking()
            }
        }
    }
}
