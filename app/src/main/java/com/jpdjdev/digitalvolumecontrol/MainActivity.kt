package com.jpdjdev.digitalvolumecontrol

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jpdjdev.digitalvolumecontrol.ui.FloatingVolumeTheme
import com.jpdjdev.digitalvolumecontrol.ui.icon
import kotlin.math.roundToInt

private const val REPO_URL = "https://github.com/JermaineJamesDev/DigitalVolumeControl"

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

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val prefs = remember { WidgetPreferences(context) }
    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    val isServiceRunning by VolumeOverlayService.isRunning.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // Re-check when returning from the system overlay-permission screen.
    LifecycleResumeEffect(Unit) {
        hasOverlayPermission = Settings.canDrawOverlays(context)
        onPauseOrDispose { }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        text = "Digital Volume Control",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (hasOverlayPermission) {
                ServiceStatusCard(
                    isServiceRunning = isServiceRunning,
                    onToggle = { enable ->
                        val intent = Intent(context, VolumeOverlayService::class.java)
                        if (enable) {
                            try {
                                context.startForegroundService(intent)
                            } catch (_: Exception) { }
                        } else {
                            context.stopService(intent)
                        }
                    }
                )

                SectionHeader("Appearance")
                AppearanceCard(prefs)

                SectionHeader("Audio streams")
                StreamsCard(prefs)
            } else {
                PermissionCard(
                    onRequestPermission = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                "package:${context.packageName}".toUri()
                            )
                        )
                    }
                )
            }

            SectionHeader("How to use")
            InstructionsCard()

            SectionHeader("About")
            AboutCard()
        }
    }
}

/* ── Shared building blocks ────────────────────────────────────────── */

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(start = 4.dp, top = 12.dp)
            .semantics { heading() }
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun SupportingText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

/* ── Permission Card ───────────────────────────────────────────────── */

@Composable
private fun PermissionCard(onRequestPermission: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Permission needed",
                    style = MaterialTheme.typography.titleLarge
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "To float above other apps, the widget needs the " +
                        "\"Display over other apps\" permission. Turn it on in the " +
                        "next screen, then come back here.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onRequestPermission,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Grant permission") }
        }
    }
}

/* ── Service Status Card ───────────────────────────────────────────── */

@Composable
private fun ServiceStatusCard(isServiceRunning: Boolean, onToggle: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val containerColor by animateColorAsState(
        if (isServiceRunning) colors.primaryContainer else colors.surfaceContainerHigh,
        label = "statusContainer"
    )
    val contentColor by animateColorAsState(
        if (isServiceRunning) colors.onPrimaryContainer else colors.onSurface,
        label = "statusContent"
    )
    val badgeColor by animateColorAsState(
        if (isServiceRunning) colors.primary else colors.surfaceContainerHighest,
        label = "statusBadge"
    )
    val badgeIconColor by animateColorAsState(
        if (isServiceRunning) colors.onPrimary else colors.onSurfaceVariant,
        label = "statusBadgeIcon"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = isServiceRunning,
                    role = Role.Switch,
                    onValueChange = onToggle
                )
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(badgeColor)
            ) {
                Icon(
                    painter = painterResource(
                        if (isServiceRunning) R.drawable.ic_volume_up else R.drawable.ic_volume_off
                    ),
                    contentDescription = null,
                    tint = badgeIconColor,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isServiceRunning) "Widget is on" else "Widget is off",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (isServiceRunning) "Floating above your other apps"
                    else "Turn on to show the floating volume bubble",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalContentColor.current.copy(alpha = 0.8f)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            // The whole row is the toggle target; the switch is visual only.
            Switch(checked = isServiceRunning, onCheckedChange = null)
        }
    }
}

/* ── Appearance Card ───────────────────────────────────────────────── */

@Composable
private fun AppearanceCard(prefs: WidgetPreferences) {
    var controlStyle by remember { mutableStateOf(prefs.controlStyle) }
    var opacity by remember { mutableFloatStateOf(prefs.widgetOpacity) }

    SettingsCard {
        Text(text = "Control style", style = MaterialTheme.typography.titleMedium)
        SupportingText("How each volume is adjusted in the expanded widget")
        Spacer(modifier = Modifier.height(12.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ControlStyle.entries.forEachIndexed { index, style ->
                SegmentedButton(
                    selected = style == controlStyle,
                    onClick = {
                        controlStyle = style
                        prefs.controlStyle = style
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, ControlStyle.entries.size)
                ) { Text(style.displayName) }
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 20.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Opacity", style = MaterialTheme.typography.titleMedium)
                SupportingText("${(opacity * 100).roundToInt()}%")
            }
            OpacityPreview(opacity)
        }
        Slider(
            value = opacity,
            onValueChange = { opacity = it },
            onValueChangeFinished = { prefs.widgetOpacity = opacity },
            valueRange = WidgetPreferences.MIN_OPACITY..WidgetPreferences.MAX_OPACITY
        )
        Text(
            text = "Applies the next time the widget opens or closes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Miniature of the collapsed bubble drawn over a striped backdrop, so the
 * chosen opacity is visible against "content" the way it is over other apps.
 * Keep the bubble styling in sync with CollapsedBubble in VolumeOverlayService.
 */
@Composable
private fun OpacityPreview(opacity: Float) {
    val stripeColor = MaterialTheme.colorScheme.outlineVariant
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width = 72.dp, height = 56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .drawBehind {
                val gap = 8.dp.toPx()
                var x = -size.height
                while (x < size.width) {
                    drawLine(
                        color = stripeColor,
                        start = Offset(x, size.height),
                        end = Offset(x + size.height, 0f),
                        strokeWidth = gap / 2
                    )
                    x += gap
                }
            }
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(36.dp)
                .graphicsLayer { alpha = opacity }
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f))
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_volume_up),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/* ── Streams Card ──────────────────────────────────────────────────── */

@Composable
private fun StreamsCard(prefs: WidgetPreferences) {
    var enabledStreams by remember { mutableStateOf(prefs.enabledStreams) }

    SettingsCard {
        SupportingText("Choose which volumes appear in the widget. At least one stays on.")
        Spacer(modifier = Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AudioStreamType.entries.forEach { stream ->
                val selected = stream in enabledStreams
                FilterChip(
                    selected = selected,
                    onClick = {
                        // Prevent deselecting the last enabled stream.
                        if (!selected || enabledStreams.size > 1) {
                            val updated = if (selected) enabledStreams - stream
                            else enabledStreams + stream
                            enabledStreams = updated
                            prefs.enabledStreams = updated
                        }
                    },
                    label = { Text(stream.label) },
                    leadingIcon = {
                        Icon(
                            imageVector = stream.icon,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize)
                        )
                    }
                )
            }
        }
    }
}

/* ── Instructions Card ─────────────────────────────────────────────── */

@Composable
private fun InstructionsCard() {
    val steps = listOf(
        "Turn the widget on. A floating bubble appears on screen.",
        "Tap the bubble to open the volume panel.",
        "Drag the bubble to move it. It snaps to the nearest edge.",
        "Drag a slider or use the buttons to change volume. Tap the speaker to mute.",
        "The panel closes by itself after a few seconds without use.",
        "Stop the widget here or from its notification."
    )

    SettingsCard {
        steps.forEachIndexed { index, step ->
            Row(modifier = Modifier.padding(vertical = 6.dp)) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = step,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

/* ── About Card ────────────────────────────────────────────────────── */

@Composable
private fun AboutCard() {
    val context = LocalContext.current
    val versionName = remember { appVersionName(context) }

    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_volume_up),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(text = "Digital Volume Control", style = MaterialTheme.typography.titleMedium)
                SupportingText("Version $versionName")
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "An on-screen alternative to hardware volume buttons. " +
                    "Free and open source under the Apache License 2.0. " +
                    "No ads, no tracking, no internet access.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedButton(
            onClick = {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, REPO_URL.toUri()))
                } catch (_: ActivityNotFoundException) { }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("View source on GitHub") }
    }
}

private fun appVersionName(context: Context): String {
    val pm = context.packageManager
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(context.packageName, 0)
    }
    return info.versionName.orEmpty()
}
