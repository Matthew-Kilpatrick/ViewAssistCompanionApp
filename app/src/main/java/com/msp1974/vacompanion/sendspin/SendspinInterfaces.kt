package com.msp1974.vacompanion.sendspin

interface SendspinController {
    val status: SendspinRuntimeStatus

    fun start()
    fun stop()
    fun onNetworkAvailable()
    fun onNetworkLost()
}
