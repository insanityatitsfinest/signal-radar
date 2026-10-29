package com.signalradar.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.signalradar.app.scan.Confidence
import com.signalradar.app.scan.Heading
import com.signalradar.app.net.AlprTracker
import com.signalradar.app.net.Prefs
import com.signalradar.app.scan.CellScanner
import com.signalradar.app.scan.ScanEngine
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    // Core permissions — the app's scanning needs these, so they gate the UI.
    private val perms: Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }.toTypedArray()

    // Requested alongside, but optional: READ_PHONE_STATE only enriches cell-tower IDs. If denied,
    // cell signal + type still work, so it must not block the app.
    private val allPermsRequested: Array<String> =
        (perms.toList() + Manifest.permission.READ_PHONE_STATE).toTypedArray()

    private var granted by mutableStateOf(false)
    private var started = false

    private val launcher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refresh()
        if (granted && !started) startScanning()
    }

    private fun refresh() {
        granted = perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun startScanning() {
        started = true
        ScanEngine.start()
        CellScanner.start(this)
        // Pull the ALPR camera map on open (fine-location granted) unless the user turned it off.
        // Only when we don't already have it, so returning from another app keeps the loaded list.
        if (Prefs.autoLoadAlpr(this) &&
            AlprTracker.cams.value.isEmpty() && !AlprTracker.loading.value &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            AlprTracker.load(this, Prefs.radius(this))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ScanEngine.init(this)
        refresh()
        setContent {
            AppTheme {
                if (granted) AppRoot() else PermissionScreen { launcher.launch(allPermsRequested) }
            }
        }
        if (!granted) launcher.launch(allPermsRequested)
    }

    override fun onStart() {
        super.onStart()
        refresh()
        Heading.start(this)
        if (granted) startScanning()
    }

    override fun onStop() {
        super.onStop()
        Heading.stop()
        ScanEngine.stop()
        CellScanner.stop()
        started = false
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Signal Radar", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Palette.green)
        Spacer(Modifier.height(16.dp))
        Text(
            "To find Bluetooth and Wi-Fi devices around you, Android requires the Nearby devices and " +
                "Location permissions. Nothing leaves your phone — scans stay on the device.",
            textAlign = TextAlign.Center, color = Palette.text,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRequest) { Text("Grant permissions") }
        Spacer(Modifier.height(12.dp))
        Text(
            "If you denied them before, open Settings → Apps → Signal Radar → Permissions.",
            textAlign = TextAlign.Center, color = Palette.muted, fontSize = 12.sp,
        )
    }
}

private enum class Tab(val label: String) { RADAR("Radar"), DEVICES("Devices"), THREATS("Threats"), TOOLS("Tools") }

@Composable
private fun AppRoot() {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var detailKey by rememberSaveable { mutableStateOf<String?>(null) }
    var lensOpen by rememberSaveable { mutableStateOf(false) }
    val devices by ScanEngine.devicesFlow.collectAsState()
    val status by ScanEngine.status.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    var locationOn by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            locationOn = ctx.getSystemService(LocationManager::class.java)?.isLocationEnabled ?: true
            delay(3000)
        }
    }

    LaunchedEffect(Unit) {
        ScanEngine.newThreat.collect { d ->
            vibrate(ctx, if (d.hasThreat(Confidence.HIGH)) 600L else 250L)
            val f = d.topFlag
            val res = snackbar.showSnackbar(
                message = "${f?.kind?.label ?: "Alert"}: ${d.displayName}",
                actionLabel = "View",
                withDismissAction = true,
            )
            if (res == SnackbarResult.ActionPerformed) detailKey = d.key
        }
    }

    BackHandler(enabled = detailKey != null || lensOpen) {
        if (lensOpen) lensOpen = false else detailKey = null
    }

    if (lensOpen) {
        LensFinderScreen(onBack = { lensOpen = false })
        return
    }
    val dk = detailKey
    if (dk != null) {
        DetailScreen(dk, devices, onBack = { detailKey = null })
        return
    }

    val threatCount = devices.count { it.hasThreat(Confidence.MEDIUM) }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = Palette.bg,
        bottomBar = {
            NavigationBar(containerColor = Palette.surface) {
                Tab.entries.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = {
                            when (t) {
                                Tab.RADAR -> Icon(Icons.Default.LocationOn, null)
                                Tab.DEVICES -> Icon(Icons.AutoMirrored.Filled.List, null)
                                Tab.THREATS -> BadgedBox(badge = { if (threatCount > 0) Badge { Text("$threatCount") } }) {
                                    Icon(Icons.Default.Warning, null)
                                }
                                Tab.TOOLS -> Icon(Icons.Default.Build, null)
                            }
                        },
                        label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = Palette.greenDim),
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                val warn = when {
                    !status.bluetoothOn -> "Bluetooth is off — turn it on to scan Bluetooth devices." to Settings.ACTION_BLUETOOTH_SETTINGS
                    !locationOn -> "Location is off — Android hides Wi-Fi and Bluetooth scan results until it's on." to Settings.ACTION_LOCATION_SOURCE_SETTINGS
                    status.message != null -> status.message!! to null
                    else -> null
                }
                if (warn != null) {
                    InfoCard("Heads up", warn.first, Palette.amber, onClick = warn.second?.let { a ->
                        { ctx.startActivity(Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    })
                }
                Box(Modifier.weight(1f)) {
                    when (Tab.entries[tab]) {
                        Tab.RADAR -> RadarScreen(devices, onOpen = { detailKey = it })
                        Tab.DEVICES -> DevicesScreen(devices, onOpen = { detailKey = it })
                        Tab.THREATS -> ThreatsScreen(devices, onOpen = { detailKey = it })
                        Tab.TOOLS -> ToolsScreen(devices, onOpen = { detailKey = it }, onLens = { lensOpen = true })
                    }
                }
            }
        }
    }
}

private fun vibrate(ctx: Context, ms: Long) {
    try {
        val vm = ctx.getSystemService(VibratorManager::class.java) ?: return
        vm.defaultVibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    } catch (_: Exception) {
    }
}
