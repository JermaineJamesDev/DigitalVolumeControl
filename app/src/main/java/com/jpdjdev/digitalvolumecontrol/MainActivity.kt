package com.jpdjdev.digitalvolumecontrol

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jpdjdev.digitalvolumecontrol.ui.FloatingVolumeTheme
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Result handled via recomposition polling */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Request notification permission on API 33+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            FloatingVolumeTheme {
                MainScreen()
            }
        }
    }

    // No need to call setContent again in onResume — Compose state handles it.
}

/* ═══════════════════════════════════════════════════════════════════════
 *  MAIN SCREEN
 * ═══════════════════════════════════════════════════════════════════════ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    val isServiceRunning by VolumeOverlayService.isRunning.collectAsStateWithLifecycle()

    // Re-check when returning from the system overlay-permission screen.
    LifecycleResumeEffect(Unit) {
        hasOverlayPermission = Settings.canDrawOverlays(context)
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Digital Volume Control") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            PermissionStatusCard(
                hasOverlayPermission = hasOverlayPermission,
                onRequestPermission = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            "package:${context.packageName}".toUri()
                        )
                    )
                }
            )

            if (hasOverlayPermission) {
                ServiceControlCard(isServiceRunning = isServiceRunning)
                WidgetSettingsCard()
            }

            InstructionsCard()
            AppInfoCard()

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/* ── Permission Card ───────────────────────────────────────────────── */

@Composable
private fun PermissionStatusCard(
    hasOverlayPermission: Boolean,
    onRequestPermission: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (hasOverlayPermission)
                MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = if (hasOverlayPermission)
                    Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = if (hasOverlayPermission)
                    MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (hasOverlayPermission)
                    "Overlay Permission Granted"
                else "Overlay Permission Required",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (hasOverlayPermission)
                    MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onErrorContainer
            )

            if (!hasOverlayPermission) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "This app needs to draw over other apps to show the floating widget.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onRequestPermission,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Grant Overlay Permission") }
            }
        }
    }
}

/* ── Service Control Card ──────────────────────────────────────────── */

@Composable
private fun ServiceControlCard(isServiceRunning: Boolean) {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (isServiceRunning) "Widget Active"
                    else "Widget Inactive",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            color = if (isServiceRunning)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                            shape = CircleShape
                        )
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        try {
                            context.startForegroundService(
                                Intent(context, VolumeOverlayService::class.java)
                            )
                        } catch (_: Exception) { }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isServiceRunning
                ) { Text("Start") }

                OutlinedButton(
                    onClick = {
                        context.stopService(
                            Intent(context, VolumeOverlayService::class.java)
                        )
                    },
                    modifier = Modifier.weight(1f),
                    enabled = isServiceRunning
                ) { Text("Stop") }
            }
        }
    }
}

/* ── Widget Settings Card ──────────────────────────────────────────── */

@Composable
private fun WidgetSettingsCard() {
    val context = LocalContext.current
    val prefs = remember { WidgetPreferences(context) }

    // Local state initialised from prefs.
    var controlStyle by remember { mutableStateOf(prefs.controlStyle) }
    var enabledStreams by remember { mutableStateOf(prefs.enabledStreams) }
    var opacity by remember { mutableFloatStateOf(prefs.widgetOpacity) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Widget Settings",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ── Control style ────────────────────────────────────
            Text(
                text = "Control Style",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ControlStyle.entries.forEach { style ->
                    val isSelected = style == controlStyle
                    Button(
                        onClick = {
                            controlStyle = style
                            prefs.controlStyle = style
                        },
                        modifier = Modifier.weight(1f),
                        colors = if (isSelected) ButtonDefaults.buttonColors()
                        else ButtonDefaults.outlinedButtonColors(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = style.displayName,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Audio streams ────────────────────────────────────
            Text(
                text = "Audio Streams",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))

            AudioStreamType.entries.forEach { stream ->
                val checked = stream in enabledStreams
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            // Prevent unchecking the last enabled stream.
                            if (checked && enabledStreams.size <= 1) return@clickable
                            val updated = enabledStreams.toMutableSet()
                            if (checked) updated.remove(stream) else updated.add(stream)
                            enabledStreams = updated
                            prefs.enabledStreams = updated
                        }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = null // handled by row click
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stream.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Opacity ──────────────────────────────────────────
            Text(
                text = "Widget Opacity",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Slider(
                    value = opacity,
                    onValueChange = { opacity = it },
                    onValueChangeFinished = { prefs.widgetOpacity = opacity },
                    valueRange = WidgetPreferences.MIN_OPACITY..WidgetPreferences.MAX_OPACITY,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${(opacity * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Opacity preview
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = opacity * 0.85f)
                    )
            ) {
                Text(
                    text = "Preview",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = opacity)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Changes apply next time the widget expands.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

/* ── Instructions Card ─────────────────────────────────────────────── */

@Composable
private fun InstructionsCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "How to use",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            val instructions = listOf(
                "Tap the floating bubble to expand controls",
                "Drag the bubble to reposition it on screen",
                "Adjust volume with sliders or buttons",
                "Tap the mute icon per stream to toggle mute",
                "Widget stays on top of other apps",
                "Stop the widget from here or from its notification"
            )
            instructions.forEach { line ->
                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                    Text(
                        "•  ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        line,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/* ── App Info Card ─────────────────────────────────────────────────── */

@Composable
private fun AppInfoCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "About",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Digital Volume Control provides a convenient floating widget for " +
                        "quick volume adjustments. Designed for users who need an on-screen " +
                        "alternative to hardware volume buttons. Configure streams, style, " +
                        "and transparency to suit your needs.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}