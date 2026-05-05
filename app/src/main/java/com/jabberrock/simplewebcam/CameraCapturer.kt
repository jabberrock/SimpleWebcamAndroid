package com.jabberrock.simplewebcam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Range
import android.view.Surface
import android.view.WindowManager
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.TextureBufferImpl
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame

class CameraCapturer(
    private val context: Context,
    private val cameraId: String,
) : VideoCapturer {

    private val cameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val characteristics = cameraManager.getCameraCharacteristics(cameraId)
    private val sensorOrientation =
        characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
    private val isFrontFacing =
        characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT

    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var capturerObserver: CapturerObserver? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var surface: Surface? = null

    override fun initialize(
        helper: SurfaceTextureHelper,
        context: Context,
        observer: CapturerObserver,
    ) {
        surfaceTextureHelper = helper
        capturerObserver = observer
    }

    @SuppressLint("MissingPermission")
    override fun startCapture(width: Int, height: Int, fps: Int) {
        val helper = surfaceTextureHelper ?: error("Not initialized")
        val observer = capturerObserver ?: error("Not initialized")

        val surfaceTexture = helper.surfaceTexture
        surfaceTexture.setDefaultBufferSize(width, height)
        helper.setTextureSize(width, height)
        val surface = Surface(surfaceTexture)
        this.surface = surface

        helper.startListening { frame ->
            val modifiedFrame = applyRotationAndMirror(frame)
            observer.onFrameCaptured(modifiedFrame)
            modifiedFrame.release()
        }

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                startSession(camera, surface, fps)
            }

            override fun onDisconnected(camera: CameraDevice) {
                Log.w(TAG, "Camera disconnected")
                camera.close()
                cameraDevice = null
            }

            override fun onError(camera: CameraDevice, error: Int) {
                Log.e(TAG, "Camera error: $error")
                camera.close()
                cameraDevice = null
                observer.onCapturerStarted(false)
            }
        }, helper.handler)
    }

    /**
     * Undo the sensor orientation from the texture matrix (and mirror for front cameras)
     * so the buffer contents are upright, then set the VideoFrame rotation to the
     * combined sensor + device orientation so the receiver can orient correctly.
     *
     * This matches what Camera2Session does in upstream WebRTC.
     */
    private fun applyRotationAndMirror(frame: VideoFrame): VideoFrame {
        val buffer = frame.buffer as? TextureBufferImpl ?: return frame.also { it.retain() }

        val transformMatrix = Matrix()
        transformMatrix.preTranslate(0.5f, 0.5f)
        if (isFrontFacing) {
            transformMatrix.preScale(-1f, 1f)
        }
        transformMatrix.preRotate(-sensorOrientation.toFloat())
        transformMatrix.preTranslate(-0.5f, -0.5f)

        val modifiedBuffer = buffer.applyTransformMatrix(
            transformMatrix, buffer.width, buffer.height
        )
        return VideoFrame(modifiedBuffer, frameOrientation, frame.timestampNs)
    }

    private val frameOrientation: Int
        get() {
            val deviceRotation = deviceOrientation
            val rotation = if (isFrontFacing) deviceRotation else (360 - deviceRotation)
            return (sensorOrientation + rotation) % 360
        }

    @Suppress("DEPRECATION")
    private val deviceOrientation: Int
        get() {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            return when (wm.defaultDisplay.rotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
        }

    @Suppress("DEPRECATION")
    private fun startSession(camera: CameraDevice, surface: Surface, fps: Int) {
        val observer = capturerObserver ?: return
        val handler = surfaceTextureHelper?.handler

        camera.createCaptureSession(
            listOf(surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    builder.addTarget(surface)
                    builder.set(
                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        Range(fps, fps),
                    )
                    session.setRepeatingRequest(builder.build(), null, handler)
                    observer.onCapturerStarted(true)
                    Log.i(TAG, "Camera capture started: $cameraId @ ${fps}fps locked")
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Camera session configuration failed")
                    session.close()
                    observer.onCapturerStarted(false)
                }
            },
            handler,
        )
    }

    override fun stopCapture() {
        surfaceTextureHelper?.stopListening()
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        surface?.release()
        surface = null
        capturerObserver?.onCapturerStopped()
    }

    override fun changeCaptureFormat(width: Int, height: Int, fps: Int) {
        stopCapture()
        startCapture(width, height, fps)
    }

    override fun dispose() {
        stopCapture()
    }

    override fun isScreencast(): Boolean = false

    companion object {
        private const val TAG = "CustomCamera2Capturer"
    }
}
