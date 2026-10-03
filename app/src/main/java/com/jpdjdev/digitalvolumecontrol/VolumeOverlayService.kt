package com.jpdjdev.digitalvolumecontrol

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.jpdjdev.digitalvolumecontrol.ui.FloatingVolumeTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.roundToInt

class VolumeOverlayService : Service(),
    LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    companion object {
        private val _isRunning = MutableStateFlow(false)

        /** Whether the widget service is currently running; observed by [MainActivity]. */
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private const val TAG = "VolumeOverlay"
        private const val NOTIFICATION_CHANNEL_ID = "volume_overlay_channel"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.jpdjdev.digitalvolumecontrol.action.STOP"

        /** Collapsed bubble size — must match [CollapsedBubble]. */
        private const val BUBBLE_SIZE_DP = 48
        private const val EDGE_MARGIN_DP = 8
        private const val DRAG_SLOP_DP = 4
    }

    /* ── Android system services ───────────────────────────────────── */
    private lateinit var windowManager: WindowManager
    private lateinit var audioManager: AudioManager
    private lateinit var overlayView: ComposeView
    private lateinit var windowParams: WindowManager.LayoutParams

    /* ── Lifecycle plumbing ────────────────────────────────────────── */
    private lateinit var lifecycleRegistry: LifecycleRegistry
    private lateinit var savedStateRegistryController: SavedStateRegistryController
    private val _viewModelStore = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = _viewModelStore

    /**
     * Shared expanded state: written by the touch-listener (tap → expand)
     * and read by Compose for UI. Both run on the main thread.
     */
    private val widgetExpanded = mutableStateOf(false)

    /** Which screen edge the widget is docked to; decided when a drag ends. */
    private var dockedRight = false

    /* ── Service lifecycle ─────────────────────────────────────────── */

    override fun onCreate() {
        super.onCreate()
        _isRunning.value = true

        // Lifecycle setup — direct extension functions, no reflection.
        lifecycleRegistry = LifecycleRegistry(this)
        savedStateRegistryController = SavedStateRegistryController.create(this)
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        createNotificationChannel()

        // Show the overlay *before* going foreground: on Android 15+ an app that
        // relies on SYSTEM_ALERT_WINDOW may only start a foreground service from
        // the background (e.g. a START_STICKY restart) while its overlay is visible.
        val overlayShown = setupOverlayView()
        val inForeground = try {
            startForeground(NOTIFICATION_ID, createNotification())
            true
        } catch (e: Exception) {
            Log.w(TAG, "Unable to start foreground service", e)
            false
        }
        if (!overlayShown || !inForeground) stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Screen size changes on rotation / fold — keep the widget on screen.
        if (::overlayView.isInitialized) applyDock()
    }

    override fun onDestroy() {
        _isRunning.value = false

        // Lifecycle teardown
        try {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        } catch (_: Exception) { }

        try { _viewModelStore.clear() } catch (_: Exception) { }

        if (::overlayView.isInitialized && overlayView.isAttachedToWindow) {
            try { windowManager.removeView(overlayView) } catch (_: Exception) { }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /* ── Notification ──────────────────────────────────────────────── */

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Volume Control Widget",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the floating volume widget active"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, VolumeOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID).apply {
            setContentTitle("Volume Control Active")
            setContentText("Tap to open settings")
            setSmallIcon(R.drawable.ic_volume_up)
            setContentIntent(openIntent)
            addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this@VolumeOverlayService, R.drawable.ic_volume_off),
                    "Stop",
                    stopIntent
                ).build()
            )
            setOngoing(true)
        }.build()
    }

    /* ── Overlay setup ─────────────────────────────────────────────── */

    /** Adds the floating widget window. Returns false if it could not be shown. */
    // Accessibility: TalkBack activates the bubble through its Compose semantics
    // onClick (see CollapsedBubble), so the raw touch listener needs no performClick.
    @SuppressLint("ClickableViewAccessibility")
    private fun setupOverlayView(): Boolean {
        windowParams = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START
            x = dp(EDGE_MARGIN_DP)
            y = dp(160)
        }

        overlayView = ComposeView(this).apply {
            // Modern direct extension functions — no reflection needed.
            setViewTreeLifecycleOwner(this@VolumeOverlayService)
            setViewTreeViewModelStoreOwner(this@VolumeOverlayService)
            setViewTreeSavedStateRegistryOwner(this@VolumeOverlayService)

            // Expanding/collapsing changes the widget width; re-dock so the
            // panel grows away from the edge and never spills off screen.
            addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
                if (right - left != oldRight - oldLeft) post { applyDock() }
            }

            /* ── Touch handling ─────────────────────────────────────
             * COLLAPSED: intercept all touches → drag to reposition,
             *            tap to expand.
             * EXPANDED:  return false immediately → Compose handles
             *            sliders, buttons, mute toggles.
             * ───────────────────────────────────────────────────── */
            var startX = 0f
            var startY = 0f
            var hasMoved = false
            val dragSlop = dp(DRAG_SLOP_DP)

            setOnTouchListener { _, event ->
                // When expanded, pass every event through to Compose.
                if (widgetExpanded.value) return@setOnTouchListener false

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        hasMoved = false
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - startX
                        val dy = event.rawY - startY
                        if (abs(dx) > dragSlop || abs(dy) > dragSlop) {
                            hasMoved = true
                            windowParams.x += dx.toInt()
                            windowParams.y += dy.toInt()
                            clampToScreen()
                            updateLayout()
                            startX = event.rawX
                            startY = event.rawY
                        }
                        true
                    }

                    MotionEvent.ACTION_UP -> {
                        if (hasMoved) {
                            snapToEdge()
                        } else {
                            // Tap → expand
                            widgetExpanded.value = true
                        }
                        true
                    }

                    else -> false
                }
            }

            setContent {
                FloatingVolumeTheme {
                    val prefs = remember { WidgetPreferences(this@VolumeOverlayService) }
                    VolumeWidget(
                        audioManager = audioManager,
                        prefs = prefs,
                        isExpanded = widgetExpanded.value,
                        onExpand = { widgetExpanded.value = true },
                        onCollapse = { widgetExpanded.value = false },
                        onOpenApp = {
                            startActivity(
                                Intent(
                                    this@VolumeOverlayService,
                                    MainActivity::class.java
                                ).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                                }
                            )
                        }
                    )
                }
            }
        }

        return try {
            windowManager.addView(overlayView, windowParams)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay view", e)
            false
        }
    }

    /* ── Positioning helpers ───────────────────────────────────────── */

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun widgetWidth(): Int = overlayView.width.takeIf { it > 0 } ?: dp(BUBBLE_SIZE_DP)

    private fun widgetHeight(): Int = overlayView.height.takeIf { it > 0 } ?: dp(BUBBLE_SIZE_DP)

    private fun clampToScreen() {
        val bounds = windowManager.currentWindowMetrics.bounds
        windowParams.x = windowParams.x.coerceIn(0, (bounds.width() - widgetWidth()).coerceAtLeast(0))
        windowParams.y = windowParams.y.coerceIn(0, (bounds.height() - widgetHeight()).coerceAtLeast(0))
    }

    /** Called when a drag ends: dock to whichever edge the bubble centre is nearer. */
    private fun snapToEdge() {
        val bounds = windowManager.currentWindowMetrics.bounds
        dockedRight = windowParams.x + widgetWidth() / 2 > bounds.width() / 2
        applyDock()
    }

    private fun applyDock() {
        val bounds = windowManager.currentWindowMetrics.bounds
        val margin = dp(EDGE_MARGIN_DP)
        windowParams.x = if (dockedRight) bounds.width() - widgetWidth() - margin else margin
        clampToScreen()
        updateLayout()
    }

    private fun updateLayout() {
        if (!overlayView.isAttachedToWindow) return
        try { windowManager.updateViewLayout(overlayView, windowParams) } catch (_: Exception) { }
    }
}

/* ═══════════════════════════════════════════════════════════════════════
 *  COMPOSABLES — Widget UI
 * ═══════════════════════════════════════════════════════════════════════ */

@Composable
private fun VolumeWidget(
    audioManager: AudioManager,
    prefs: WidgetPreferences,
    isExpanded: Boolean,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onOpenApp: () -> Unit
) {
    // Re-read preferences each time expansion state changes.
    val controlStyle = remember(isExpanded) { prefs.controlStyle }
    val streams = remember(isExpanded) { prefs.enabledStreams.toList().sortedBy { it.ordinal } }
    val opacity = remember(isExpanded) { prefs.widgetOpacity }

    Box {
        // ── Collapsed bubble ─────────────────────────────────────
        // Always composed but only visible when collapsed, so the
        // shared MutableState<Boolean> toggle is instant.
        AnimatedVisibility(
            visible = !isExpanded,
            enter = fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 0.6f),
            exit = fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.6f)
        ) {
            CollapsedBubble(opacity = opacity, onExpand = onExpand)
        }

        // ── Expanded panel ───────────────────────────────────────
        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 0.7f),
            exit = fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.7f)
        ) {
            ExpandedPanel(
                audioManager = audioManager,
                streams = streams,
                controlStyle = controlStyle,
                opacity = opacity,
                onCollapse = onCollapse,
                onOpenApp = onOpenApp
            )
        }
    }
}

/* ── Collapsed Bubble ──────────────────────────────────────────────── */

@Composable
private fun CollapsedBubble(opacity: Float, onExpand: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            // Touches are handled by the service's touch listener (drag / tap);
            // this only exposes a click action to accessibility services.
            .semantics {
                onClick(label = "Show volume controls") { onExpand(); true }
            }
            .graphicsLayer { alpha = opacity }
            .clip(CircleShape)
            .background(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                shape = CircleShape
            )
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.25f),
                shape = CircleShape
            )
    ) {
        Icon(
            painterResource(R.drawable.ic_volume_up),
            contentDescription = "Volume Control",
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.onPrimary
        )
    }
}

/* ── Expanded Panel ────────────────────────────────────────────────── */

@Composable
private fun ExpandedPanel(
    audioManager: AudioManager,
    streams: List<AudioStreamType>,
    controlStyle: ControlStyle,
    opacity: Float,
    onCollapse: () -> Unit,
    onOpenApp: () -> Unit
) {
    // Auto-collapse timer — resets on any user interaction.
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lastInteraction) {
        delay(8000)
        onCollapse()
    }

    val panelWidth = when {
        controlStyle == ControlStyle.BUTTONS -> 200.dp
        streams.size <= 2 -> 140.dp
        streams.size <= 3 -> 190.dp
        else -> 240.dp
    }

    Surface(
        modifier = Modifier
            .widthIn(min = 130.dp, max = 260.dp)
            .width(panelWidth)
            .graphicsLayer {
                alpha = opacity
                shadowElevation = 12f
                shape = RoundedCornerShape(20.dp)
                clip = true
            },
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shadowElevation = 8.dp,
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(20.dp)
                )
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Volume",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                IconButton(
                    onClick = onCollapse,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Collapse",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            when (controlStyle) {
                ControlStyle.SLIDER -> SliderControls(
                    audioManager = audioManager,
                    streams = streams,
                    onInteraction = { lastInteraction = System.currentTimeMillis() }
                )

                ControlStyle.BUTTONS -> ButtonControls(
                    audioManager = audioManager,
                    streams = streams,
                    onInteraction = { lastInteraction = System.currentTimeMillis() }
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // "Open app" link
            Text(
                text = "Open app",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onOpenApp() }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

/* ── Stream volume state ───────────────────────────────────────────── */

/**
 * Volume of one audio stream, kept in sync with [AudioManager].
 *
 * Every write re-reads the real level because the system can refuse a change
 * (e.g. muting Ring/Notification while Do Not Disturb is active throws a
 * [SecurityException]); the UI then shows what actually happened.
 */
@Stable
private class StreamVolumeState(
    private val audioManager: AudioManager,
    private val streamType: Int
) {
    val maxVolume: Int = audioManager.getStreamMaxVolume(streamType).coerceAtLeast(1)

    var volume by mutableIntStateOf(audioManager.getStreamVolume(streamType))
        private set

    val isMuted: Boolean get() = volume == 0
    val percent: Int get() = (volume * 100f / maxVolume).roundToInt()

    private var restoreVolume = (maxVolume / 2).coerceAtLeast(1)

    fun set(value: Int) {
        try {
            audioManager.setStreamVolume(streamType, value.coerceIn(0, maxVolume), 0)
        } catch (e: SecurityException) {
            Log.w("VolumeOverlay", "Volume change refused for stream $streamType", e)
        }
        refresh()
    }

    fun adjust(direction: Int) {
        try {
            audioManager.adjustStreamVolume(streamType, direction, 0)
        } catch (e: SecurityException) {
            Log.w("VolumeOverlay", "Volume change refused for stream $streamType", e)
        }
        refresh()
    }

    fun toggleMute() {
        if (isMuted) {
            set(restoreVolume)
        } else {
            restoreVolume = volume
            set(0)
        }
    }

    fun refresh() {
        volume = audioManager.getStreamVolume(streamType)
    }
}

@Composable
private fun rememberStreamVolumeState(
    audioManager: AudioManager,
    stream: AudioStreamType
): StreamVolumeState {
    val state = remember(stream) { StreamVolumeState(audioManager, stream.streamType) }
    // Poll while visible (catches external changes like HW buttons).
    LaunchedEffect(state) {
        while (true) {
            delay(600)
            state.refresh()
        }
    }
    return state
}

@DrawableRes
private fun muteIcon(isMuted: Boolean): Int =
    if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up

/* ── Slider-mode controls ──────────────────────────────────────────── */

@Composable
private fun SliderControls(
    audioManager: AudioManager,
    streams: List<AudioStreamType>,
    onInteraction: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        streams.forEach { stream ->
            StreamSliderColumn(
                audioManager = audioManager,
                stream = stream,
                onInteraction = onInteraction
            )
        }
    }
}

@Composable
private fun StreamSliderColumn(
    audioManager: AudioManager,
    stream: AudioStreamType,
    onInteraction: () -> Unit
) {
    val state = rememberStreamVolumeState(audioManager, stream)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(44.dp)
    ) {
        // Percentage
        Text(
            text = "${state.percent}",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Vertical slider
        VerticalSlider(
            value = state.volume.toFloat(),
            onValueChange = { newVal ->
                val v = newVal.roundToInt()
                if (v != state.volume) {
                    state.set(v)
                    onInteraction()
                }
            },
            valueRange = 0f..state.maxVolume.toFloat(),
            steps = (state.maxVolume - 1).coerceAtLeast(0),
            modifier = Modifier
                .height(110.dp)
                .width(28.dp),
            activeColor = if (state.isMuted)
                MaterialTheme.colorScheme.error
            else
                MaterialTheme.colorScheme.primary,
            inactiveColor = MaterialTheme.colorScheme.outlineVariant
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Mute toggle
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(
                    if (state.isMuted) MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                    else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                )
                .clickable {
                    onInteraction()
                    state.toggleMute()
                }
        ) {
            Icon(
                painterResource(muteIcon(state.isMuted)),
                contentDescription = if (state.isMuted) "Unmute ${stream.label}"
                else "Mute ${stream.label}",
                modifier = Modifier.size(16.dp),
                tint = if (state.isMuted) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = stream.shortLabel,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
    }
}

/* ── Button-mode controls ──────────────────────────────────────────── */

@Composable
private fun ButtonControls(
    audioManager: AudioManager,
    streams: List<AudioStreamType>,
    onInteraction: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        streams.forEach { stream ->
            StreamButtonRow(
                audioManager = audioManager,
                stream = stream,
                onInteraction = onInteraction
            )
        }
    }
}

@Composable
private fun StreamButtonRow(
    audioManager: AudioManager,
    stream: AudioStreamType,
    onInteraction: () -> Unit
) {
    val state = rememberStreamVolumeState(audioManager, stream)

    Column {
        // Stream label + percentage
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stream.shortLabel,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            )
            Text(
                text = "${state.percent}%",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (state.isMuted) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Control row: [Mute] [−] [+]
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Mute toggle
            FilledTonalButton(
                onClick = {
                    onInteraction()
                    state.toggleMute()
                },
                modifier = Modifier.size(34.dp),
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(
                    painterResource(muteIcon(state.isMuted)),
                    contentDescription = if (state.isMuted) "Unmute ${stream.label}"
                    else "Mute ${stream.label}",
                    modifier = Modifier.size(16.dp)
                )
            }

            // Volume down
            FilledTonalButton(
                onClick = {
                    onInteraction()
                    state.adjust(AudioManager.ADJUST_LOWER)
                },
                modifier = Modifier.size(34.dp),
                shape = CircleShape,
                enabled = state.volume > 0,
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(
                    painterResource(R.drawable.ic_remove),
                    contentDescription = "${stream.label} volume down",
                    modifier = Modifier.size(16.dp)
                )
            }

            // Volume up
            FilledTonalButton(
                onClick = {
                    onInteraction()
                    state.adjust(AudioManager.ADJUST_RAISE)
                },
                modifier = Modifier.size(34.dp),
                shape = CircleShape,
                enabled = state.volume < state.maxVolume,
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(
                    painterResource(R.drawable.ic_add),
                    contentDescription = "${stream.label} volume up",
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/* ── Vertical Slider helper ────────────────────────────────────────── */

/**
 * Rotates a standard horizontal [Slider] by 270° and swaps its layout
 * dimensions so it occupies correct vertical space. Standard Compose pattern.
 */
@Composable
private fun VerticalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    inactiveColor: Color = MaterialTheme.colorScheme.outlineVariant
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        steps = steps,
        colors = SliderDefaults.colors(
            thumbColor = activeColor,
            activeTrackColor = activeColor,
            inactiveTrackColor = inactiveColor
        ),
        modifier = modifier
            .graphicsLayer {
                rotationZ = 270f
                transformOrigin = TransformOrigin(0.5f, 0.5f)
            }
            .layout { measurable, constraints ->
                val placeable = measurable.measure(
                    Constraints(
                        minWidth = constraints.minHeight,
                        maxWidth = constraints.maxHeight,
                        minHeight = constraints.minWidth,
                        maxHeight = constraints.maxWidth
                    )
                )
                layout(placeable.height, placeable.width) {
                    placeable.place(
                        x = -(placeable.width - placeable.height) / 2,
                        y = -(placeable.height - placeable.width) / 2
                    )
                }
            }
    )
}
