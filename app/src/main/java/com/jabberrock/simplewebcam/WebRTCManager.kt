package com.jabberrock.simplewebcam

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
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
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds

class WebRTCManager(
    private val context: Context,
    private val rotationSensor: RotationSensor,
) {

    private val _isActive = MutableStateFlow(false)
    val isActive = _isActive.asStateFlow()

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
                _isActive.value = true
                runVideoCapturer(connectRequest, peerConnectionFactory)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "WebRTCManager encountered exception, ignoring: ${e.message}", e)
                // Swallow exception
            } finally {
                connectRequest.onAnswerSdp.cancel()
                _isActive.value = false
            }
        }
    }

    private suspend fun runVideoCapturer(
        connectRequest: ConnectRequest,
        peerConnectionFactory: PeerConnectionFactory
    ) {
        val videoSource = peerConnectionFactory.createVideoSource(true)

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        } ?: error("Failed to find camera")

        val videoCapturer = CameraCapturer(context, cameraId)
        val surfaceTextureHelper =
            SurfaceTextureHelper.create("CaptureThread", eglBaseContext)
        videoCapturer.initialize(
            surfaceTextureHelper,
            context,
            videoSource.capturerObserver
        )

        val cameraIntrinsic = cameraIntrinsicsForCamera(cameraId, VIDEO_WIDTH, VIDEO_HEIGHT)

        Log.i(TAG, "Starting video capturer...")
        videoCapturer.startCapture(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS)
        try {
            runPeerConnection(connectRequest, peerConnectionFactory, videoSource, cameraIntrinsic)
        } finally {
            videoCapturer.stopCapture()
            videoCapturer.dispose()
            Log.i(TAG, "Video capturer stopped")
        }
    }

    private suspend fun runPeerConnection(
        connectRequest: ConnectRequest,
        peerConnectionFactory: PeerConnectionFactory,
        videoSource: VideoSource,
        cameraIntrinsic: CameraIntrinsicJSON
    ) {
        Log.i(TAG, "Creating peer connection...")

        val rtcConfig = PeerConnection.RTCConfiguration(emptyList())
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        rtcConfig.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE

        val observer =
            PeerConnectionHandler(this, cameraIntrinsic, currentCoroutineContext())
        val peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, observer)
            ?: error("Failed to create peer connection")
        this.peerConnection.set(peerConnection)

        observer.peerConnection = peerConnection

        try {
            Log.i(TAG, "Setting offer SDP...")
            val offer = SessionDescription(SessionDescription.Type.OFFER, connectRequest.offerSdp)
            awaitSdpSet { peerConnection.setRemoteDescription(it, offer) }

            peerConnection.setBitrate(null, START_BITRATE_BPS, MAX_BITRATE_BPS)

            val videoTrack = peerConnectionFactory.createVideoTrack("video0", videoSource)
            previewSink?.let { videoTrack.addSink(it) }
            peerConnection.addTrack(videoTrack)

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

    private fun cameraIntrinsicsForCamera(
        cameraId: String,
        imageWidth: Int,
        imageHeight: Int,
    ): CameraIntrinsicJSON {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)

        val calibration = characteristics.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
            ?: error("Intrinsic calibration unavailable")
        val active = characteristics.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
            ?: error("PRE_CORRECTION_ACTIVE_ARRAY unavailable")

        if (calibration.size < 4) {
            error("Intrinsic calibration malformed")
        }

        val iw = imageWidth.toDouble()
        val ih = imageHeight.toDouble()

        val activeFx = calibration[0].toDouble()
        val activeFy = calibration[1].toDouble()
        val activeTx = calibration[2].toDouble()
        val activeTy = calibration[3].toDouble()

        val activeWidth = active.width().toDouble()
        val activeHeight = active.height().toDouble()

        //
        // https://source.android.com/docs/core/camera/camera3_crop_reprocess
        // > In all cases, the stream crop must be centered within the full crop
        // > region, and each stream is only either cropped horizontally or
        // > vertical relative to the full crop region, never both.
        //
        // This means that the real sensor area that is used for the image is
        // centered within the active array, and scaled to fit the active array.
        //
        val sensorPixelsPerImagePixel = min(activeWidth / iw, activeHeight / ih)

        val imageFx = activeFx / sensorPixelsPerImagePixel
        val imageFy = activeFy / sensorPixelsPerImagePixel

        //
        // https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#LENS_INTRINSIC_CALIBRATION
        // > Note that the coordinate system for this transform is the
        // > android.sensor.info.preCorrectionActiveArraySize system, where
        // > (0,0) is the top-left of the preCorrectionActiveArraySize
        // > rectangle.
        //
        // After we scale to fit the image within the active array, the top-left
        // of the real sensor region may not be (0,0) of the active array. So we
        // need to remove that offset from tx and ty.
        //
        val imageTx = activeTx / sensorPixelsPerImagePixel - (activeWidth / sensorPixelsPerImagePixel - imageWidth) * 0.5
        val imageTy = activeTy / sensorPixelsPerImagePixel - (activeHeight / sensorPixelsPerImagePixel - imageHeight) * 0.5

        return CameraIntrinsicJSON(
            fx = imageFx,
            fy = imageFy,
            tx = imageTx,
            ty = imageTy,
            width = imageWidth,
            height = imageHeight,
        )
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

    private class PeerConnectionHandler(
        private val self: WebRTCManager,
        private val cameraIntrinsic: CameraIntrinsicJSON,
        private val coroutineContext: CoroutineContext,
    ) : PeerConnection.Observer {
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

        override fun onDataChannel(dataChannel: DataChannel) {
            Log.i(TAG, "Data channel ${dataChannel.label()} connected")

            if (dataChannel.label() == VISION_DATA_CHANNEL) {
                dataChannel.registerObserver(
                    VisionDataChannelHandler(self, dataChannel, cameraIntrinsic, coroutineContext))
            } else {
                dataChannel.registerObserver(
                    DataChannelHandler(self, dataChannel)
                )
            }
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

    private class VisionDataChannelHandler(
        self: WebRTCManager,
        dataChannel: DataChannel,
        private val cameraIntrinsic: CameraIntrinsicJSON,
        private val coroutineContext: CoroutineContext,
    ) : DataChannelHandler(self, dataChannel) {

        override fun onBufferedAmountChange(p0: Long) {
            // Do nothing
        }

        override fun onStateChange() {
            super.onStateChange()

            if (dataChannel.state() == DataChannel.State.OPEN) {
                CoroutineScope(coroutineContext).launch {
                    runVisionDataChannel(
                        dataChannel,
                        cameraIntrinsic,
                    )
                }
            }
        }

        override fun onMessage(p0: DataChannel.Buffer?) {
            // Do nothing
        }

        private suspend fun runVisionDataChannel(
            dataChannel: DataChannel,
            cameraIntrinsic: CameraIntrinsicJSON,
        ) {
            while (currentCoroutineContext().isActive) {
                val payload =
                    VisionJsonSerializer.encodeToString(
                        VisionJSON(
                            cameraIntrinsic = cameraIntrinsic,
                            deviceToArbitraryZVertical =
                                self.rotationSensor.deviceToArbitraryZVertical.value,
                        ),
                    )
                dataChannel.send(
                    DataChannel.Buffer(
                        ByteBuffer.wrap(payload.toByteArray(StandardCharsets.UTF_8)),
                        false,
                    )
                )
                delay(SEND_VISION_DATA_INTERVAL)
            }
        }
    }

    private open class DataChannelHandler(
        val self: WebRTCManager,
        val dataChannel: DataChannel,
    ) : DataChannel.Observer {

        override fun onBufferedAmountChange(p0: Long) {
            // Do nothing
        }

        override fun onStateChange() {
            if (dataChannel.state() == DataChannel.State.CLOSED) {
                val peerConnection = self.peerConnection.get()
                peerConnection?.close()
            }
        }

        override fun onMessage(p0: DataChannel.Buffer?) {
            // Do nothing
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
        private const val VISION_DATA_CHANNEL = "vision"
        private val SEND_VISION_DATA_INTERVAL = 1.seconds

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

        private val VisionJsonSerializer = Json.Default

        @Serializable
        private data class VisionJSON(
            val cameraIntrinsic: CameraIntrinsicJSON,
            val deviceToArbitraryZVertical: Quaternion,
        )

        @Serializable
        private data class CameraIntrinsicJSON(
            val fx: Double,
            val fy: Double,
            val tx: Double,
            val ty: Double,
            val width: Int,
            val height: Int,
        )
    }
}
