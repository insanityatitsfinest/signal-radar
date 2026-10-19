package com.signalradar.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun LensFinderScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    var front by remember { mutableStateOf(false) }
    var torch by remember { mutableStateOf(true) }
    var filter by remember { mutableStateOf(true) }
    var camera by remember { mutableStateOf<Camera?>(null) }

    val previewView = remember {
        PreviewView(ctx).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE // TextureView, so the color filter applies
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(granted, front) {
        var provider: ProcessCameraProvider? = null
        if (granted) {
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                try {
                    val p = future.get()
                    provider = p
                    val preview = Preview.Builder().build()
                    preview.setSurfaceProvider(previewView.surfaceProvider)
                    p.unbindAll()
                    camera = p.bindToLifecycle(
                        owner,
                        if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                    )
                } catch (_: Exception) {
                    camera = null
                }
            }, ContextCompat.getMainExecutor(ctx))
        }
        onDispose {
            try { provider?.unbindAll() } catch (_: Exception) {}
            camera = null
        }
    }

    LaunchedEffect(camera, torch, front) {
        try { camera?.cameraControl?.enableTorch(torch && !front) } catch (_: Exception) {}
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize(), update = { v ->
                if (filter) {
                    // Keep mostly red and crush the shadows so the glint of a lens or IR LED pops out
                    val m = ColorMatrix(
                        floatArrayOf(
                            1.8f, 0.3f, 0.3f, 0f, -140f,
                            0.15f, 0.15f, 0.15f, 0f, -20f,
                            0.15f, 0.15f, 0.15f, 0f, -20f,
                            0f, 0f, 0f, 1f, 0f,
                        )
                    )
                    v.setLayerType(View.LAYER_TYPE_HARDWARE, Paint().apply { colorFilter = ColorMatrixColorFilter(m) })
                } else {
                    v.setLayerType(View.LAYER_TYPE_NONE, null)
                }
            })
        } else {
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Camera permission is needed for the lens finder.", color = Color.White)
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.padding(top = 12.dp)) { Text("Allow camera") }
            }
        }

        Row(Modifier.statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            Text("Lens finder", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.65f))
                .navigationBarsPadding().padding(12.dp)
        ) {
            Text(
                if (front) "Front camera: look for purple/white dots — infrared LEDs from night-vision cameras. Turn the room lights off."
                else "Lights off. Hold the phone at eye level and slowly scan. A camera lens throws back a sharp, " +
                    "steady pinpoint of light that stays put as you move a little — glass and shiny objects smear or vanish.",
                color = Color.White, fontSize = 13.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                FilterChip(selected = torch, onClick = { torch = !torch }, label = { Text("Flashlight") }, enabled = !front)
                FilterChip(selected = filter, onClick = { filter = !filter }, label = { Text("Glint filter") })
                FilterChip(selected = front, onClick = { front = !front }, label = { Text("Front / IR") })
            }
        }
    }
}
