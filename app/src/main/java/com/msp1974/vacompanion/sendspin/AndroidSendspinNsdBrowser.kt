package com.msp1974.vacompanion.sendspin

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.sendspin.protocol.NsdBrowser
import com.sendspin.protocol.NsdServiceEvent
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import timber.log.Timber

internal class AndroidSendspinNsdBrowser(
    private val context: Context,
) : NsdBrowser {

    override fun browse(serviceType: String): Flow<NsdServiceEvent> = callbackFlow {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        var discoveryActive = false

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Timber.w("Sendspin NSD resolve failed: ${serviceInfo.serviceName} ($errorCode)")
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host?.hostAddress ?: return
                val port = serviceInfo.port
                trySend(NsdServiceEvent.ServiceResolved(serviceInfo.serviceName, host, port))
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                discoveryActive = true
                Timber.d("Sendspin NSD discovery started for $regType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                trySend(NsdServiceEvent.ServiceFound(serviceInfo.serviceName))
                nsdManager.resolveService(serviceInfo, resolveListener)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                trySend(NsdServiceEvent.ServiceLost(serviceInfo.serviceName))
            }

            override fun onDiscoveryStopped(serviceType: String) {
                discoveryActive = false
                Timber.d("Sendspin NSD discovery stopped for $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                trySend(NsdServiceEvent.BrowseError(errorCode))
                close(IllegalStateException("NSD discovery start failed: $errorCode"))
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Timber.w("Sendspin NSD stop discovery failed: $errorCode")
                if (discoveryActive) {
                    runCatching { nsdManager.stopServiceDiscovery(this) }
                }
            }
        }

        runCatching {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        }.onFailure {
            close(it)
        }

        awaitClose {
            if (discoveryActive) {
                runCatching {
                    nsdManager.stopServiceDiscovery(discoveryListener)
                }.onFailure {
                    Timber.w("Sendspin NSD stop error: ${it.message}")
                }
            }
        }
    }
}
