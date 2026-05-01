package com.jabberrock.simplewebcam

import android.util.Log
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.calllogging.processingTimeMillis
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receive
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicReference

class WebServer {
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
                }
            )
        if (!started) {
            error("WebServer is already running")
        }
    }

    fun cancel() {
        job.get()?.cancel()
    }

    private fun Application.module() {
        install(ContentNegotiation) {
            json()
        }
        install(CallLogging) {
            format { call ->
                val msg = "${call.request.httpMethod.value} ${call.request.uri} -> ${call.response.status()} (${call.processingTimeMillis()} ms)"
                Log.i(TAG, msg)
                msg
            }
        }
        install(StatusPages) {
            exception<CustomException> { call, cause ->
                Log.w(TAG, "${call.request.httpMethod.value} ${call.request.uri} threw uncaught exception: $cause", cause)
                call.respond(cause.key.statusCode, ErrorResponse(cause.key))
            }
            exception<Throwable> { call, cause ->
                Log.w(TAG, "${call.request.httpMethod.value} ${call.request.uri} threw uncaught exception: $cause", cause)
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse(CustomErrorKey.UNKNOWN))
            }
        }
        routing {
            post("/connect") {
                call.handlePostConnect()
            }
        }
    }

    private suspend fun run() {
        Log.i(TAG, "Starting web server...")

        val engine =
            embeddedServer(
                factory = CIO,
                port = DEFAULT_PORT,
                module = { module() },
            )

        try {
            engine.startSuspend(false)
            awaitCancellation()
        } finally {
            withContext(NonCancellable) {
                engine.stopSuspend(STOP_GRACE_PERIOD_MS, STOP_TIMEOUT_MS)
                Log.i(TAG, "Web server stopped")
            }
        }
    }

    private enum class CustomErrorKey(val statusCode: HttpStatusCode, val message: String) {
        UNKNOWN(HttpStatusCode.InternalServerError, "An unknown error occurred"),
        INVALID_REQUEST_BODY(HttpStatusCode.BadRequest, "Invalid request body"),
    }

    private class CustomException(val key: CustomErrorKey, cause: Throwable? = null): Exception(key.message, cause)

    @Serializable
    private data class ErrorResponse(val key: CustomErrorKey) {
        val message = key.message
    }

    @Serializable
    private data class ConnectRequest(val offerSdp: String)

    @Serializable
    private data class ConnectResponse(val answerSdp: String)

    private suspend fun ApplicationCall.handlePostConnect() {
        val request =
            try {
                receive<ConnectRequest>()
            } catch (e: Exception) {
                throw CustomException(CustomErrorKey.INVALID_REQUEST_BODY, e)
            }

        if (request.offerSdp != "OFFER SDP") {
            throw CustomException(CustomErrorKey.INVALID_REQUEST_BODY)
        }

        respond(HttpStatusCode.OK, ConnectResponse("ANSWER SDP"))
    }

    companion object {
        private const val TAG = "WebServer"
        private const val DEFAULT_PORT = 8080
        private const val STOP_GRACE_PERIOD_MS = 500L
        private const val STOP_TIMEOUT_MS = 3_000L
    }
}
