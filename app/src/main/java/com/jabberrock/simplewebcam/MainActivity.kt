package com.jabberrock.simplewebcam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.jabberrock.simplewebcam.ui.theme.SimpleWebcamTheme
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

class MainActivity : ComponentActivity() {

    private val webRTCManager by lazy { WebRTCManager(applicationContext) }
    private val webServer by lazy { WebServer(webRTCManager) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                webRTCManager.startOn(owner.lifecycleScope)
                webServer.startOn(owner.lifecycleScope)
            }

            override fun onStop(owner: LifecycleOwner) {
                webRTCManager.cancel()
                webServer.cancel()
            }
        })

        setContent {
            SimpleWebcamTheme {
                CameraApp(webRTCManager)
            }
        }
    }
}

@Composable
private fun CameraApp(webRTCManager: WebRTCManager) {
    val context = LocalContext.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    CameraPreview(webRTCManager)
}

@Composable
private fun CameraPreview(webRTCManager: WebRTCManager) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                SurfaceViewRenderer(ctx).also {
                    webRTCManager.attachPreview(it)
                    it.setMirror(true)
                    it.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                }
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = {
                webRTCManager.detachPreview(it)
                it.release()
            },
        )
    }
}
