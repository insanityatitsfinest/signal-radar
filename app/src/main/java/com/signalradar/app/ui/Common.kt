package com.signalradar.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.signalradar.app.scan.Confidence
import com.signalradar.app.scan.Device
import com.signalradar.app.scan.Flag
import com.signalradar.app.scan.Rssi
import com.signalradar.app.scan.Source
import com.signalradar.app.scan.ageText
import com.signalradar.app.scan.shortVendor

object Palette {
    val bg = Color(0xFF050B08)
    val surface = Color(0xFF0C1712)
    val surface2 = Color(0xFF13221A)
    val green = Color(0xFF3DFF8C)
    val greenDim = Color(0xFF1C6B40)
    val text = Color(0xFFE3F5EA)
    val muted = Color(0xFF8DA898)
    val ble = Color(0xFF4DE3FF)
    val classic = Color(0xFF6E8BFF)
    val wifi = Color(0xFFFFD54D)
    val lan = Color(0xFFC77DFF)
    val alpr = Color(0xFFFF8A3D)
    val red = Color(0xFFFF4D4D)
    val amber = Color(0xFFFFB020)
    val PINEAPPLE = Color(0xFFE040FB)
}

val AppColors = darkColorScheme(
    primary = Palette.green,
    onPrimary = Color(0xFF00210E),
    secondary = Palette.ble,
    background = Palette.bg,
    onBackground = Palette.text,
    surface = Palette.surface,
    onSurface = Palette.text,
    surfaceVariant = Palette.surface2,
    onSurfaceVariant = Palette.muted,
    surfaceContainer = Palette.surface,
    surfaceContainerHigh = Palette.surface2,
    surfaceContainerLow = Palette.surface,
    error = Palette.red,
    outline = Palette.greenDim,
)

fun sourceColor(s: Source) = when (s) {
    Source.BLE -> Palette.ble
    Source.CLASSIC -> Palette.classic
    Source.WIFI -> Palette.wifi
    Source.LAN -> Palette.lan
    Source.ALPR -> Palette.alpr
    Source.PINEAPPLE -> Palette.PINEAPPLE
}

fun confColor(c: Confidence) = when (c) {
    Confidence.HIGH -> Palette.red
    Confidence.MEDIUM -> Palette.amber
    Confidence.LOW -> Palette.muted
}

@Composable
fun Tag(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun SignalBars(rssi: Float?, color: Color, modifier: Modifier = Modifier) {
    val s = rssi?.let { Rssi.strength(it) } ?: 0f
    Canvas(modifier.size(22.dp, 18.dp)) {
        val bw = size.width / 4.8f
        for (i in 0 until 4) {
            val h = size.height * (i + 1) / 4f
            val on = rssi != null && s >= i / 4f + 0.05f
            drawRoundRect(
                color = if (on) color else color.copy(alpha = 0.18f),
                topLeft = Offset(i * bw * 1.25f, size.height - h),
                size = Size(bw, h),
                cornerRadius = CornerRadius(2f, 2f),
            )
        }
    }
}

@Composable
fun FlagLine(f: Flag) {
    Row(verticalAlignment = Alignment.Top) {
        Tag(f.confidence.label, confColor(f.confidence))
        Spacer(Modifier.width(6.dp))
        Column {
            Text(f.kind.label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = confColor(f.confidence))
            Text(f.reason, fontSize = 12.sp, color = Palette.muted)
        }
    }
}

@Composable
fun DeviceRow(d: Device, now: Long, onClick: () -> Unit) {
    val c = sourceColor(d.source)
    val flag = d.topFlag
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = Palette.surface),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Tag(d.source.label, c)
                    if (flag != null && flag.confidence != Confidence.LOW) Tag(flag.kind.label, confColor(flag.confidence))
                }
                Spacer(Modifier.padding(top = 4.dp))
                Text(d.displayName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(d.vendor?.let { shortVendor(it) }, d.category).distinct().joinToString(" · "),
                    fontSize = 12.sp, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(d.address, fontSize = 11.sp, color = Palette.muted.copy(alpha = 0.7f), fontFamily = FontFamily.Monospace, maxLines = 1)
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                if (d.smoothRssi != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${d.rssi} dBm", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c)
                        Spacer(Modifier.width(6.dp))
                        SignalBars(d.smoothRssi, c)
                    }
                }
                Text("~" + Rssi.formatDistance(d.estDistanceM), fontSize = 12.sp, color = Palette.text)
                if (d.source != Source.ALPR && d.source != Source.LAN) {
                    Text(ageText(now - d.lastSeen), fontSize = 11.sp, color = Palette.muted)
                }
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), modifier = modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp),
        color = Palette.green, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
    )
}

@Composable
fun InfoCard(title: String, body: String, color: Color = Palette.green, onClick: (() -> Unit)? = null) {
    Card(
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.10f)),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, color = color, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(body, color = Palette.text, fontSize = 13.sp)
        }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AppColors, content = content)
}
