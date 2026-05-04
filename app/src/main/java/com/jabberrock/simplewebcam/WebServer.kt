package com.jabberrock.simplewebcam

import android.content.Context
import android.util.Log
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.CachingOptions
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.cachingheaders.CachingHeaders
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.calllogging.processingTimeMillis
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receive
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
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
import kotlin.text.Charsets

class WebServer(
    private val port: Int,
    private val context: Context,
    private val webRTCManager: WebRTCManager,
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
        install(CachingHeaders) {
            options { call, content ->
                CachingOptions(CacheControl.NoCache(CacheControl.Visibility.Private))
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
            get("/") {
                call.respondText(loadWebAsset(WEB_INDEX_HTML), ContentType.Text.Html)
            }
            get("/viewer.js") {
                call.respondText(loadWebAsset(WEB_VIEWER_JS), ContentType.Application.JavaScript)
            }
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
                port = port,
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
        WEBRTC_SESSION_ALREADY_ACTIVE(HttpStatusCode.Conflict, "A WebRTC session is already active"),
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

        val answerSdp: String
        try {
            answerSdp = webRTCManager.connect(request.offerSdp)
        } catch (e: WebRTCManager.WebRTCSessionAlreadyActive) {
            throw CustomException(CustomErrorKey.WEBRTC_SESSION_ALREADY_ACTIVE, e)
        }

        respond(HttpStatusCode.OK, ConnectResponse(answerSdp))
    }

    private fun loadWebAsset(relativeAssetPath: String) =
        context.assets.open(relativeAssetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }

    companion object {
        private const val TAG = "WebServer"
        private const val STOP_GRACE_PERIOD_MS = 500L
        private const val STOP_TIMEOUT_MS = 3_000L
        private const val WEB_INDEX_HTML = "web/index.html"
        private const val WEB_VIEWER_JS = "web/viewer.js"
    }
}
