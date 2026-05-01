package com.jabberrock.simplewebcam

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

class MdnsPublisher(
    private val context: Context,
    private val port: Int,
) {
    private val job = AtomicReference<Job?>(null)

    fun startOn(scope: CoroutineScope) {
        val started =
            job.compareAndSet(
                null,
                scope.launch {
                    try {
                        run()
                    } finally {
                        job.set(null)
                    }
                },
            )
        if (!started) {
            error("MdnsPublisher is already running")
        }
    }

    fun cancel() {
        job.get()?.cancel()
    }

    private suspend fun run() {
        val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

        val publishPort = port

        val serviceInfo =
            NsdServiceInfo().apply {
                serviceName = SERVICE_INSTANCE_NAME
                serviceType = SERVICE_TYPE
                port = publishPort
            }

        val registrationReady = CompletableDeferred<Unit>()

        val listener =
            object : NsdManager.RegistrationListener {
                override fun onRegistrationFailed(si: NsdServiceInfo, errorCode: Int) {
                    registrationReady.completeExceptionally(
                        IllegalStateException("$TAG registration failed with errorCode=$errorCode"))
                }

                override fun onUnregistrationFailed(si: NsdServiceInfo?, errorCode: Int) {
                    Log.w(TAG, "Unregistration failed: errorCode=$errorCode")
                }

                override fun onServiceRegistered(si: NsdServiceInfo) {
                    Log.i(TAG, "Registered serviceName=${si.serviceName} type=${si.serviceType} port=${si.port}")
                    registrationReady.complete(Unit)
                }

                override fun onServiceUnregistered(si: NsdServiceInfo) {
                    Log.i(TAG, "Unregistered serviceName=${si.serviceName}")
                }
            }

        nsd.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)

        try {
            registrationReady.await()
            awaitCancellation()
        } finally {
            withContext(NonCancellable) {
                nsd.unregisterService(listener)
            }
        }
    }

    companion object {
        private const val TAG = "MdnsPublisher"
        private const val SERVICE_TYPE = "_simple_webcam._tcp."
        private const val SERVICE_INSTANCE_NAME = "Simple Webcam"
    }
}
