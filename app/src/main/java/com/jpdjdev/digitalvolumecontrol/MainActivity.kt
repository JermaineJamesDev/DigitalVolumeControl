package com.jpdjdev.digitalvolumecontrol

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.core.os.bundleOf
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jpdjdev.digitalvolumecontrol.ui.FloatingVolumeTheme
import com.jpdjdev.digitalvolumecontrol.ui.icon
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val REPO_URL = "https://github.com/JermaineJamesDev/DigitalVolumeControl"

/** How long the service may take to connect before it is reported as stopped. */
private const val SERVICE_CONNECT_GRACE_MS = 3_000L

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

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
    val isServiceRunning by VolumeOverlayService.isRunning.collectAsStateWithLifecycle()
    var isWidgetVisible by remember { mutableStateOf(prefs.widgetVisible) }

    // Re-read on resume: the user toggles the service in system settings.
    // Seeded synchronously so the "turn on" card does not flash on launch.
    var isServiceEnabled by remember {
        mutableStateOf(VolumeOverlayService.isEnabledInSettings(context))
    }
    LifecycleResumeEffect(Unit) {
        isServiceEnabled = VolumeOverlayService.isEnabledInSettings(context)
        onPauseOrDispose { }
    }

    // Right after the user switches the service on (or after the app process
    // restarts) the system takes a moment to connect it. Only report it as
    // stopped if it is still not running after a grace period.
    var connectTimedOut by remember { mutableStateOf(false) }
    LaunchedEffect(isServiceEnabled, isServiceRunning) {
        connectTimedOut = false
        if (isServiceEnabled && !isServiceRunning) {
            delay(SERVICE_CONNECT_GRACE_MS)
            connectTimedOut = true
        }
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

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
            if (isServiceRunning) {
                ServiceStatusCard(
                    isWidgetVisible = isWidgetVisible,
                    onToggle = { visible ->
                        // The service observes this preference and adds or
                        // removes the bubble immediately.
                        isWidgetVisible = visible
                        prefs.widgetVisible = visible
                    }
                )

                SectionHeader("Appearance")
                AppearanceCard(prefs)

                SectionHeader("Audio streams")
                StreamsCard(prefs)
            } else if (isServiceEnabled && !connectTimedOut) {
                ServiceStartingCard()
            } else if (isServiceEnabled) {
                ServiceStoppedCard(
                    onOpenAccessibilitySettings = { openAccessibilitySettings(context) },
                    onOpenAppInfo = { openAppInfo(context) },
                    onOpenAutostart = { openXiaomiAutostart(context) }
                )
            } else {
                EnableServiceCard(
                    onOpenAccessibilitySettings = { openAccessibilitySettings(context) },
                    onOpenAppInfo = { openAppInfo(context) },
                    onOpenAutostart = { openXiaomiAutostart(context) }
                )
            }

            SectionHeader("Keep it running")
            KeepRunningCard(
                onOpenAppInfo = { openAppInfo(context) },
                onOpenAutostart = { openXiaomiAutostart(context) }
            )

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

/**
 * Opens a system settings screen and returns whether it opened. Some OEM
 * builds omit a few screens or do not export them.
 */
private fun openSettings(context: Context, intent: Intent): Boolean =
    try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

/**
 * Opens Accessibility settings. The extras ask the Settings app to scroll to
 * and highlight this service; they are not public API, so builds that do not
 * understand them (e.g. some MIUI / HyperOS versions) just ignore them. The
 * direct per-service page needs a system-only permission.
 */
private fun openAccessibilitySettings(context: Context) {
    val service = ComponentName(context, VolumeOverlayService::class.java).flattenToString()
    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        .putExtra(":settings:fragment_args_key", service)
        .putExtra(":settings:show_fragment_args", bundleOf(":settings:fragment_args_key" to service))
    openSettings(context, intent)
}

private fun openAppInfo(context: Context) {
    openSettings(
        context,
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "package:${context.packageName}".toUri()
        )
    )
}

/** Redmi and POCO devices also report Xiaomi as the manufacturer. */
private val isXiaomi: Boolean = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)

/**
 * MIUI / HyperOS Security app's Autostart list. Not a public API, so it can
 * move between versions; falls back to App info, which also links to it.
 */
private fun openXiaomiAutostart(context: Context) {
    val intent = Intent().setComponent(
        ComponentName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity"
        )
    )
    if (!openSettings(context, intent)) openAppInfo(context)
}

/**
 * Describes why the app's process last ended, from the records Android keeps
 * (API 30+). Returns null if there is no record.
 */
private fun lastStopReason(context: Context): String? {
    val am = context.getSystemService(ActivityManager::class.java) ?: return null
    val info = am.getHistoricalProcessExitReasons(null, 0, 1).firstOrNull() ?: return null
    val reason = when (info.reason) {
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "The app crashed"
        ApplicationExitInfo.REASON_ANR -> "The app stopped responding"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "Android closed it to free up memory"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "It was force stopped or cleared from recent apps"
        ApplicationExitInfo.REASON_SIGNALED,
        ApplicationExitInfo.REASON_OTHER,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE ->
            "The system or a battery saver closed it"
        else -> "It stopped for another reason"
    }
    val ago = DateUtils.getRelativeTimeSpanString(
        info.timestamp,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS
    )
    // The raw description (e.g. an OEM kill reason) helps with bug reports.
    val detail = info.description?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
    return "$reason, $ago.$detail"
}

/* ── Enable Service Card ───────────────────────────────────────────── */

@Composable
private fun EnableServiceCard(
    onOpenAccessibilitySettings: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onOpenAutostart: () -> Unit
) {
    WarningCard(title = "Turn on the service") {
        Text(
            text = "The bubble runs as an accessibility service, so it stays on " +
                    "screen without any notifications. Turn on Digital Volume " +
                    "Control in the next screen, then come back here.",
            style = MaterialTheme.typography.bodyMedium
        )
        if (isXiaomi) XiaomiAutostartStep(onOpenAutostart)
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onOpenAccessibilitySettings,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Open accessibility settings") }
        RestrictedSettingsHelp(onOpenAppInfo)
    }
}

/* ── Service Starting Card ─────────────────────────────────────────── */

@Composable
private fun ServiceStartingCard() {
    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
            Spacer(modifier = Modifier.width(16.dp))
            Text(text = "Starting the service…", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/* ── Service Stopped Card ──────────────────────────────────────────── */

/**
 * Shown when the service is switched on in settings but not connected. The
 * system shows it as "not working" (or an error on MIUI) after the process
 * dies, and does not always restart it, so the user has to toggle it.
 */
@Composable
private fun ServiceStoppedCard(
    onOpenAccessibilitySettings: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onOpenAutostart: () -> Unit
) {
    val context = LocalContext.current
    val stopReason = remember { lastStopReason(context) }

    WarningCard(title = "The service has stopped") {
        Text(
            text = "It is switched on in Accessibility settings, but Android is not " +
                    "running it, so the bubble is gone. Open Accessibility settings, " +
                    "turn Digital Volume Control off, then on again. To stop it " +
                    "happening again, follow the steps under Keep it running below.",
            style = MaterialTheme.typography.bodyMedium
        )
        if (stopReason != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Last stop: $stopReason",
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (isXiaomi) XiaomiAutostartStep(onOpenAutostart)
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onOpenAccessibilitySettings,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Open accessibility settings") }
        RestrictedSettingsHelp(onOpenAppInfo)
    }
}

/* ── Setup help shared by the warning cards ────────────────────────── */

@Composable
private fun WarningCard(title: String, content: @Composable ColumnScope.() -> Unit) {
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
                Text(text = title, style = MaterialTheme.typography.titleLarge)
            }
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

/**
 * With Autostart off, MIUI / HyperOS can refuse to start the app's process
 * for the service, so the switch turns on and then fails straight away.
 */
@Composable
private fun XiaomiAutostartStep(onOpenAutostart: () -> Unit) {
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = "On Xiaomi, Redmi and POCO phones, turn on Autostart for this app " +
                "first. Without it the phone may refuse to start the service, and " +
                "it shows an error or switches itself off.",
        style = MaterialTheme.typography.bodyMedium
    )
    Spacer(modifier = Modifier.height(12.dp))
    OutlinedButton(
        onClick = onOpenAutostart,
        modifier = Modifier.fillMaxWidth()
    ) { Text("Open Autostart settings") }
}

/**
 * Android 13+ blocks accessibility services of apps installed from an APK
 * until the user allows restricted settings. The App info menu item only
 * appears after the user has tried the switch once and been refused.
 */
@Composable
private fun RestrictedSettingsHelp(onOpenAppInfo: () -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = "Switch greyed out, or a \"Restricted setting\" message? Android " +
                "does this for apps installed from an APK. Tap the switch once so " +
                "that message appears, then open App info, tap the three-dot menu " +
                "at the top, choose \"Allow restricted settings\", and turn the " +
                "service on again.",
        style = MaterialTheme.typography.bodyMedium
    )
    Spacer(modifier = Modifier.height(12.dp))
    OutlinedButton(
        onClick = onOpenAppInfo,
        modifier = Modifier.fillMaxWidth()
    ) { Text("Open app info") }
}

/* ── Service Status Card ───────────────────────────────────────────── */

@Composable
private fun ServiceStatusCard(isWidgetVisible: Boolean, onToggle: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val containerColor by animateColorAsState(
        if (isWidgetVisible) colors.primaryContainer else colors.surfaceContainerHigh,
        label = "statusContainer"
    )
    val contentColor by animateColorAsState(
        if (isWidgetVisible) colors.onPrimaryContainer else colors.onSurface,
        label = "statusContent"
    )
    val badgeColor by animateColorAsState(
        if (isWidgetVisible) colors.primary else colors.surfaceContainerHighest,
        label = "statusBadge"
    )
    val badgeIconColor by animateColorAsState(
        if (isWidgetVisible) colors.onPrimary else colors.onSurfaceVariant,
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
                    value = isWidgetVisible,
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
                        if (isWidgetVisible) R.drawable.ic_volume_up else R.drawable.ic_volume_off
                    ),
                    contentDescription = null,
                    tint = badgeIconColor,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isWidgetVisible) "Bubble is showing" else "Bubble is hidden",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (isWidgetVisible) "Floating above your other apps"
                    else "The service stays on, ready to show it again",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalContentColor.current.copy(alpha = 0.8f)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            // The whole row is the toggle target; the switch is visual only.
            Switch(checked = isWidgetVisible, onCheckedChange = null)
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
                Text(text = "Bubble opacity", style = MaterialTheme.typography.titleMedium)
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
            text = "The volume panel always opens fully visible.",
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

/* ── Keep Running Card ─────────────────────────────────────────────── */

/**
 * Some OEM builds (Xiaomi HyperOS/MIUI in particular) kill background apps to
 * save battery, which can also switch off the accessibility service. Exempting
 * the app has to be done by the user from App info.
 */
@Composable
private fun KeepRunningCard(onOpenAppInfo: () -> Unit, onOpenAutostart: () -> Unit) {
    SettingsCard {
        SupportingText(
            "Some phones, such as Xiaomi, Redmi and POCO, close apps in the background " +
                    "to save battery and may switch the service off. In App info, turn " +
                    "on Autostart and set Battery saver to No restrictions. On Xiaomi, " +
                    "also lock the app in Recent apps (long-press its card) so clearing " +
                    "recent apps does not close it, and under Other permissions allow " +
                    "\"Display pop-up windows while running in the background\" so the " +
                    "panel's settings button can open this screen."
        )
        Spacer(modifier = Modifier.height(12.dp))
        if (isXiaomi) {
            Button(
                onClick = onOpenAutostart,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Open Autostart settings") }
            Spacer(modifier = Modifier.height(8.dp))
        }
        OutlinedButton(
            onClick = onOpenAppInfo,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Open app info") }
    }
}

/* ── Instructions Card ─────────────────────────────────────────────── */

@Composable
private fun InstructionsCard() {
    val steps = listOf(
        "Turn on the accessibility service. A floating bubble appears on screen.",
        "Tap the bubble to open the volume panel.",
        "Drag the bubble to move it. It snaps to the nearest edge.",
        "Drag a slider or use the buttons to change volume. Tap the speaker to mute.",
        "Tap anywhere outside the panel to close it. It also closes by itself after a few seconds without use.",
        "Hide the bubble with the switch here. To turn the service off, use Accessibility settings."
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
