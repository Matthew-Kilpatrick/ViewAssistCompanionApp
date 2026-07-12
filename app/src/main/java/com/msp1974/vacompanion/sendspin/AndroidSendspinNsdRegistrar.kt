package com.msp1974.vacompanion.sendspin

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.sendspin.protocol.NsdRegistrar
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import timber.log.Timber

internal class AndroidSendspinNsdRegistrar(
    private val context: Context,
) : NsdRegistrar {

    override fun register(
        serviceName: String,
        serviceType: String,
        port: Int,
        txt: Map<String, String>,
    ): Flow<Unit> = callbackFlow {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

        val serviceInfo = NsdServiceInfo().apply {
            this.serviceName = serviceName
            // Android NSD expects service type without trailing dot.
            this.serviceType = serviceType.removeSuffix(".")
            this.port = port
            txt.forEach { (key, value) ->
                setAttribute(key, value)
            }
        }

        var registeredServiceName = serviceInfo.serviceName
        var registered = false

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(nsdServiceInfo: NsdServiceInfo) {
                registered = true
                registeredServiceName = nsdServiceInfo.serviceName
                Timber.i(
                    "Sendspin NSD registered: %s (%s:%d)",
                    registeredServiceName,
                    serviceInfo.serviceType,
                    serviceInfo.port,
                )
                trySend(Unit)
            }

            override fun onRegistrationFailed(nsdServiceInfo: NsdServiceInfo, errorCode: Int) {
                Timber.e("Sendspin NSD registration failed (%d): %s", errorCode, nsdServiceInfo.serviceName)
                close(IllegalStateException("NSD registration failed: $errorCode"))
            }

            override fun onServiceUnregistered(nsdServiceInfo: NsdServiceInfo) {
                registered = false
                Timber.i("Sendspin NSD unregistered: %s", nsdServiceInfo.serviceName)
            }

            override fun onUnregistrationFailed(nsdServiceInfo: NsdServiceInfo, errorCode: Int) {
                Timber.w("Sendspin NSD unregistration failed (%d): %s", errorCode, nsdServiceInfo.serviceName)
            }
        }

        runCatching {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure {
            close(it)
        }

        awaitClose {
            if (registered) {
                runCatching {
                    nsdManager.unregisterService(listener)
                }.onFailure {
                    Timber.w("Sendspin NSD unregister error: %s", it.message)
                }
            }
        }
    }
}
