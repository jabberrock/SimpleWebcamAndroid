package com.jabberrock.simplewebcam

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.webrtc.Camera2Enumerator
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.seconds
import org.webrtc.RendererCommon

class WebRTCManager(private val context: Context) {

    private val eglBase = EglBase.create()
    private val eglBaseContext = eglBase.eglBaseContext

    private var previewSink: VideoSink? = null

    fun attachPreview(view: SurfaceViewRenderer) {
        view.init(eglBaseContext, null)
        previewSink = view
    }

    fun detachPreview(view: SurfaceViewRenderer) {
        if (previewSink === view) {
            previewSink = null
        }
    }

    private data class ConnectRequest(
        val offerSdp: String,
        val onAnswerSdp: CompletableDeferred<String>
    )

    private val connectRequest = Channel<ConnectRequest>(Channel.RENDEZVOUS)

    private val job = AtomicReference<Job?>(null)
    private val peerConnection = AtomicReference<PeerConnection?>(null)

    class WebRTCSessionAlreadyActive: Exception("A WebRTC session is already active")

    /**
     * Starts the manager.
     *
     * Can be called from any thread.
     */
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
            error("WebRTCManager is already running")
        }
    }

    /**
     * Requests the manager to connect to a WebRTC peer.
     *
     * @throws IllegalStateException if the manager is already connected to a peer.
     *
     * Can be called from any thread.
     */
    suspend fun connect(offerSdp: String): String {
        val request = ConnectRequest(offerSdp, CompletableDeferred())
        if (connectRequest.trySend(request).isFailure) {
            throw WebRTCSessionAlreadyActive()
        }

        return request.onAnswerSdp.await()
    }

    /**
     * Requests the manager to disconnect from the current WebRTC peer.
     *
     * Can be called from any thread.
     */
    fun disconnect() {
        peerConnection.get()?.close()
    }

    /**
     * Stops the manager.
     *
     * Can be called from any thread.
     */
    fun cancel() {
        job.get()?.cancel()
    }

    private suspend fun run() {
        initNativeLibraries(context)

        val peerConnectionFactory =
            PeerConnectionFactory.builder()
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBaseContext))
                .createPeerConnectionFactory()

        while (true) {
            val connectRequest = connectRequest.receive()
            try {
                runVideoCapturer(connectRequest, peerConnectionFactory)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "WebRTCManager encountered exception, ignoring: ${e.message}", e)
                // Swallow exception
            } finally {
                connectRequest.onAnswerSdp.cancel()
            }
        }
    }

    private suspend fun runVideoCapturer(
        connectRequest: ConnectRequest,
        peerConnectionFactory: PeerConnectionFactory
    ) {
        val videoSource = peerConnectionFactory.createVideoSource(true)

        val videoCapturer = createVideoCapturer()
        val surfaceTextureHelper =
            SurfaceTextureHelper.create("CaptureThread", eglBaseContext)
        videoCapturer.initialize(
            surfaceTextureHelper,
            context,
            videoSource.capturerObserver
        )

        Log.i(TAG, "Starting video capturer...")
        videoCapturer.startCapture(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS)
        try {
            runPeerConnection(connectRequest, peerConnectionFactory, videoSource)
        } finally {
            videoCapturer.stopCapture()
            videoCapturer.dispose()
            Log.i(TAG, "Video capturer stopped")
        }
    }

    private suspend fun runPeerConnection(
        connectRequest: ConnectRequest,
        peerConnectionFactory: PeerConnectionFactory,
        videoSource: VideoSource
    ) {
        Log.i(TAG, "Creating peer connection...")

        val rtcConfig = PeerConnection.RTCConfiguration(emptyList())
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        rtcConfig.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE

        val observer = PeerConnectionHandler()
        val peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, observer)
            ?: error("Failed to create peer connection")
        this.peerConnection.set(peerConnection)

        observer.peerConnection = peerConnection

        try {
            peerConnection.setBitrate(null, START_BITRATE_BPS, MAX_BITRATE_BPS)

            val videoTrack = peerConnectionFactory.createVideoTrack("video0", videoSource)
            previewSink?.let { videoTrack.addSink(it) }
            peerConnection.addTrack(videoTrack)

            Log.i(TAG, "Setting offer SDP...")
            val offer = SessionDescription(SessionDescription.Type.OFFER, connectRequest.offerSdp)
            awaitSdpSet { peerConnection.setRemoteDescription(it, offer) }

            Log.i(TAG, "Creating answer SDP...")
            val answer = awaitSdpCreate { peerConnection.createAnswer(it, MediaConstraints()) }

            Log.i(TAG, "Setting answer SDP...")
            awaitSdpSet { peerConnection.setLocalDescription(it, answer) }

            Log.i(TAG, "Waiting for ICE gathering to complete...")
            withTimeout(ICE_GATHERING_TIMEOUT) { observer.iceGatheringComplete.await() }

            val answerSdp = peerConnection.localDescription?.description
                ?: error("Missing local SDP after ICE gathering")

            connectRequest.onAnswerSdp.complete(answerSdp)

            Log.i(TAG, "Waiting for WebRTC peer to connect...")
            observer.isConnected.await()
            Log.i(TAG, "WebRTC peer is connected")

            observer.isComplete.await()
            Log.i(TAG, "WebRTC peer connection is complete")

        } finally {
            peerConnection.close()
            peerConnection.dispose()
            this.peerConnection.set(null)
        }
    }

    private fun createVideoCapturer(): VideoCapturer {
        val cameras = Camera2Enumerator(context)

        val frontCameraId = cameras.deviceNames.firstOrNull { cameras.isFrontFacing(it) }
        if (frontCameraId == null) {
            error("Failed to find camera")
        }

        return cameras.createCapturer(frontCameraId, null)
    }

    private suspend fun awaitSdpSet(action: (SdpObserver) -> Unit) {
        suspendCancellableCoroutine { cont ->
            action(object : SdpObserver {
                override fun onSetSuccess() = cont.resume(Unit)
                override fun onSetFailure(error: String?) =
                    cont.resumeWithException(RuntimeException("SDP set failed: $error"))
                override fun onCreateSuccess(sdp: SessionDescription?) {}
                override fun onCreateFailure(error: String?) {}
            })
        }
    }

    private suspend fun awaitSdpCreate(action: (SdpObserver) -> Unit): SessionDescription {
        return suspendCancellableCoroutine { cont ->
            action(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) = cont.resume(sdp!!)
                override fun onCreateFailure(error: String?) =
                    cont.resumeWithException(RuntimeException("SDP create failed: $error"))
                override fun onSetSuccess() {}
                override fun onSetFailure(error: String?) {}
            })
        }
    }

    private class PeerConnectionHandler : PeerConnection.Observer {
        var peerConnection: PeerConnection? = null

        val iceGatheringComplete = CompletableDeferred<Unit>()
        val isConnected = CompletableDeferred<Unit>()
        val isComplete = CompletableDeferred<Unit>()

        override fun onSignalingChange(p0: PeerConnection.SignalingState?) {
        }

        override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {
        }

        override fun onIceConnectionReceivingChange(p0: Boolean) {
        }

        override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {
            if (p0 == PeerConnection.IceGatheringState.COMPLETE) {
                iceGatheringComplete.complete(Unit)
            }
        }

        override fun onIceCandidate(p0: IceCandidate?) {

        }

        override fun onIceCandidatesRemoved(p0: Array<out IceCandidate?>?) {
        }

        override fun onAddStream(p0: MediaStream?) {
        }

        override fun onRemoveStream(p0: MediaStream?) {
        }

        override fun onDataChannel(p0: DataChannel?) {
        }

        override fun onRenegotiationNeeded() {
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> {
                    isConnected.complete(Unit)
                }
                PeerConnection.PeerConnectionState.FAILED -> {
                    peerConnection?.close()
                    isComplete.completeExceptionally(RuntimeException("WebRTC peer connection failed"))
                }
                PeerConnection.PeerConnectionState.CLOSED -> {
                    isComplete.complete(Unit)
                }
                else -> {}
            }
        }
    }

    companion object {
        private const val TAG = "WebRTCManager"
        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720
        private const val VIDEO_FPS = 30
        private const val START_BITRATE_BPS = 2_000_000
        private const val MAX_BITRATE_BPS = 4_000_000
        private val ICE_GATHERING_TIMEOUT = 5.seconds

        private var shouldInitNative = true

        private fun initNativeLibraries(context: Context) {
            if (shouldInitNative) {
                val options =
                    PeerConnectionFactory
                        .InitializationOptions
                        .builder(context.applicationContext)
                        .createInitializationOptions()
                PeerConnectionFactory.initialize(options)
                shouldInitNative = false
            }
        }
    }
}