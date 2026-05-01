package com.jabberrock.simplewebcam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.jabberrock.simplewebcam.ui.theme.SimpleWebcamTheme
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

internal const val WEB_SERVER_PORT = 8080

class MainActivity : ComponentActivity() {

    private val webRTCManager by lazy { WebRTCManager(applicationContext) }
    private val webServer by lazy { WebServer(WEB_SERVER_PORT, applicationContext, webRTCManager) }

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
                Box(modifier = Modifier.fillMaxSize()) {
                    CameraApp(webRTCManager)
                    NetworkAddressesBanner(
                        modifier =
                            Modifier
                                .align(Alignment.TopStart)
                                .statusBarsPadding()
                                .padding(start = 12.dp, top = 8.dp),
                    )
                }
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
    var isActive by remember { mutableStateOf(false) }
    LaunchedEffect(webRTCManager) {
        webRTCManager.isActive.collect { isActive = it }
    }

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

        if (!isActive) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.White.copy(alpha = 0.0f),
                                    Color.Black.copy(alpha = 1.0f),
                                ),
                            ),
                        ),
            )
        }
    }
}

@Composable
private fun NetworkAddressesBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var urls by remember { mutableStateOf<List<String>>(emptyList()) }

    fun refresh() {
        urls = siteLocalIpv4Addresses().map { host -> "http://$host:$WEB_SERVER_PORT" }
    }

    DisposableEffect(context) {
        refresh()
        val cm =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    refresh()
                }

                override fun onLost(network: Network) {
                    refresh()
                }

                override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                    refresh()
                }
            }
        cm.registerDefaultNetworkCallback(cb)
        onDispose { cm.unregisterNetworkCallback(cb) }
    }

    val style = MaterialTheme.typography.bodyLarge
    Column(
        modifier =
            modifier
                .background(
                    color = Color.Black.copy(alpha = 0.52f),
                    shape = RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text = "Connect to webcam:",
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(modifier = Modifier.height(6.dp))
        urls.forEach { url ->
            Text(
                text = url,
                color = Color.White,
                style = style,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (urls.isEmpty()) {
            Text(
                text = "Phone is not connected to local network",
                color = Color.White,
                style = style,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private fun siteLocalIpv4Addresses(): List<String> =
    runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .asSequence()
            .flatMap { Collections.list(it.inetAddresses).asSequence() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
            .sorted()
            .toList()
    }.getOrElse { emptyList() }
