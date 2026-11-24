package com.example.remoteacess

import android.content.Context
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.remoteacess.capture.ScreenCaptureService
import com.example.remoteacess.net.FrameClient
import com.example.remoteacess.net.NetworkUtils
import com.example.remoteacess.ui.theme.RemoteAcessTheme

private enum class Screen { Home, Host, Viewer }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RemoteAcessTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    AppRoot(Modifier.padding(padding))
                }
            }
        }
    }
}

@Composable
private fun AppRoot(modifier: Modifier = Modifier) {
    var screen by remember { mutableStateOf(Screen.Home) }
    Box(modifier.fillMaxSize()) {
        when (screen) {
            Screen.Home -> HomeScreen(
                onHost = { screen = Screen.Host },
                onViewer = { screen = Screen.Viewer },
            )
            Screen.Host -> HostScreen(onBack = { screen = Screen.Home })
            Screen.Viewer -> ViewerScreen(onBack = { screen = Screen.Home })
        }
    }
}

@Composable
private fun HomeScreen(onHost: () -> Unit, onViewer: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Remote Access", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Both phones must be on the same Wi-Fi.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onHost, modifier = Modifier.fillMaxWidth()) {
            Text("Share my screen (Host)")
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onViewer, modifier = Modifier.fillMaxWidth()) {
            Text("View another screen (Viewer)")
        }
    }
}

@Composable
private fun HostScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var sharing by remember { mutableStateOf(ScreenCaptureService.isRunning) }
    val ip = remember { NetworkUtils.getLocalIpAddress() }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == android.app.Activity.RESULT_OK && data != null) {
            ContextCompat.startForegroundService(
                context,
                ScreenCaptureService.startIntent(context, result.resultCode, data),
            )
            sharing = true
        }
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* proceed regardless; notification is best-effort */
        launchProjection(context, projectionLauncher::launch)
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Host", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(24.dp))

        if (!sharing) {
            Text(
                "Tapping Start will ask Android for permission to capture your screen.",
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        launchProjection(context, projectionLauncher::launch)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Start sharing") }
        } else {
            Text("● Sharing is active", color = Color(0xFF2E7D32))
            Spacer(Modifier.height(16.dp))
            Text("On the viewer phone, enter:")
            Spacer(Modifier.height(8.dp))
            Text(
                text = ip ?: "IP unavailable (check Wi-Fi)",
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Monospace,
            )
            Text("port ${ScreenCaptureService.PORT}")
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    context.startService(ScreenCaptureService.stopIntent(context))
                    sharing = false
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Stop sharing") }
        }

        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
}

private fun launchProjection(context: Context, launch: (android.content.Intent) -> Unit) {
    val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    launch(mpm.createScreenCaptureIntent())
}

@Composable
private fun ViewerScreen(onBack: () -> Unit) {
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val client = remember { FrameClient() }
    var host by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Not connected") }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var connected by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { client.disconnect() }
    }

    val currentFrame = frame
    if (currentFrame != null) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Image(
                bitmap = currentFrame,
                contentDescription = "Remote screen",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            OutlinedButton(
                onClick = {
                    client.disconnect()
                    connected = false
                    frame = null
                    onBack()
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
            ) { Text("Disconnect") }
        }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Viewer", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Host IP address") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(status, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                if (host.isBlank()) return@Button
                connected = true
                client.connect(
                    host = host.trim(),
                    port = ScreenCaptureService.PORT,
                    onFrame = { bmp -> mainHandler.post { frame = bmp.asImageBitmap() } },
                    onStatus = { s -> mainHandler.post { status = s } },
                )
            },
            enabled = !connected,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Connect") }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = {
                client.disconnect()
                connected = false
                onBack()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Back") }
    }
}
