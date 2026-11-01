package com.jpdjdev.digitalvolumecontrol

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.AudioManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
import com.jpdjdev.digitalvolumecontrol.ui.icon
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Hosts the floating widget as an accessibility overlay.
 *
 * An accessibility service is used instead of a foreground service with
 * SYSTEM_ALERT_WINDOW because the system keeps it bound for as long as the
 * user leaves it enabled, without an ongoing notification, and accessibility
 * overlays do not trigger the system "displaying over other apps" notification.
 * The service requests no accessibility events and cannot read window content
 * (see res/xml/accessibility_service_config.xml); it only draws the widget.
 */
class VolumeOverlayService : AccessibilityService(),
    LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    companion object {
        private val _isRunning = MutableStateFlow(false)

        /** Whether the accessibility service is connected; observed by [MainActivity]. */
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        /**
         * Whether the user has switched the service on in Accessibility settings.
         * This stays true after the process dies, so on while not [isRunning]
         * means the system lost the service (crash, or killed in the background).
         */
        fun isEnabledInSettings(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val self = ComponentName(context, VolumeOverlayService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == self }
        }

        private const val TAG = "VolumeOverlay"

        /** Collapsed bubble size — must match [CollapsedBubble]. */
        private const val BUBBLE_SIZE_DP = 48
        private const val EDGE_MARGIN_DP = 8
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

    /**
     * Set when the panel closes. A tall panel near the bottom is pushed up to
     * fit on screen, so once it has shrunk back to the bubble (see the layout
     * listener) the bubble returns to its saved position.
     */
    private var restoreYOnShrink = false

    /** Tallest the panel may grow: the screen height between the system bars. */
    private val maxPanelHeight = mutableStateOf(Dp.Unspecified)

    private lateinit var prefs: WidgetPreferences

    /** Settings shown by the widget, refreshed whenever a preference changes. */
    private val widgetSettings = mutableStateOf(WidgetSettings())

    // Held in a field: SharedPreferences only keeps a weak reference to it.
    // The key is null when preferences are cleared, so that re-reads everything.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // Written by this service at the end of every drag; nothing to refresh.
        if (WidgetPreferences.isPositionKey(key)) return@OnSharedPreferenceChangeListener
        widgetSettings.value = WidgetSettings.from(prefs)
        applyVisibility()
    }

    /* ── Service lifecycle ─────────────────────────────────────────── */

    override fun onCreate() {
        super.onCreate()

        // Lifecycle setup — direct extension functions, no reflection.
        lifecycleRegistry = LifecycleRegistry(this)
        savedStateRegistryController = SavedStateRegistryController.create(this)
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // A repeated connect would otherwise add a second, unremovable overlay.
        if (::overlayView.isInitialized) return

        // An exception escaping this method kills the process; the system then
        // reports the service as "not working" and stops restarting it, which
        // the user sees as the switch turning on and immediately failing. Keep
        // the service alive instead; MainActivity shows it as not running.
        try {
            // Fetched after connecting so the WindowManager carries the
            // accessibility window token required for TYPE_ACCESSIBILITY_OVERLAY.
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            prefs = WidgetPreferences(this)
            widgetSettings.value = WidgetSettings.from(prefs)

            setupOverlayView()
            prefs.registerChangeListener(prefsListener)
            applyVisibility()
            _isRunning.value = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set up the overlay", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::overlayView.isInitialized) return
        // Screen size changes on rotation / fold — keep the widget on screen.
        updateMaxPanelHeight()
        if (!widgetExpanded.value) restoreSavedPosition()
        applyDock()
    }

    override fun onDestroy() {
        _isRunning.value = false
        if (::prefs.isInitialized) prefs.unregisterChangeListener(prefsListener)

        // Remove the window first so the composition is disposed while its
        // lifecycle is still alive, rather than after ON_DESTROY.
        if (::overlayView.isInitialized && overlayView.isAttachedToWindow) {
            try { windowManager.removeViewImmediate(overlayView) } catch (_: Exception) { }
        }

        try {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        } catch (_: Exception) { }

        try { _viewModelStore.clear() } catch (_: Exception) { }

        super.onDestroy()
    }

    /* ── Overlay setup ─────────────────────────────────────────────── */

    /** Creates the floating widget view; [applyVisibility] adds it to the screen. */
    // Accessibility: TalkBack activates the bubble through its Compose semantics
    // onClick (see CollapsedBubble), so the raw touch listener needs no performClick.
    @SuppressLint("ClickableViewAccessibility")
    private fun setupOverlayView() {
        windowParams = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START
        }
        restoreSavedPosition()
        updateMaxPanelHeight()

        overlayView = ComposeView(this).apply {
            // Modern direct extension functions — no reflection needed.
            setViewTreeLifecycleOwner(this@VolumeOverlayService)
            setViewTreeViewModelStoreOwner(this@VolumeOverlayService)
            setViewTreeSavedStateRegistryOwner(this@VolumeOverlayService)

            // Expanding/collapsing changes the widget size; re-dock so the
            // panel grows away from the edge and never spills off screen.
            addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                val resized = right - left != oldRight - oldLeft ||
                        bottom - top != oldBottom - oldTop
                if (resized) post {
                    if (restoreYOnShrink && !widgetExpanded.value) {
                        restoreYOnShrink = false
                        restoreSavedPosition()
                    }
                    applyDock()
                }
            }

            /* ── Touch handling ─────────────────────────────────────
             * COLLAPSED: intercept all touches → drag to reposition,
             *            tap to expand.
             * EXPANDED:  return false immediately → Compose handles
             *            sliders, buttons, mute toggles.
             * ───────────────────────────────────────────────────── */
            // Positions are taken relative to the touch-down point, not summed
            // per event, so rounding never makes the bubble drift off the finger.
            var downRawX = 0f
            var downRawY = 0f
            var downX = 0
            var downY = 0
            var dragging = false
            val dragSlop = ViewConfiguration.get(this@VolumeOverlayService).scaledTouchSlop

            setOnTouchListener { _, event ->
                // Only delivered while expanded (FLAG_WATCH_OUTSIDE_TOUCH);
                // a tap anywhere else on screen closes the panel.
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    collapse()
                    return@setOnTouchListener true
                }

                // When expanded, pass every event through to Compose.
                if (widgetExpanded.value) return@setOnTouchListener false

                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        downX = windowParams.x
                        downY = windowParams.y
                        dragging = false
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downRawX
                        val dy = event.rawY - downRawY
                        if (!dragging && (abs(dx) > dragSlop || abs(dy) > dragSlop)) {
                            dragging = true
                        }
                        if (dragging) {
                            windowParams.x = downX + dx.roundToInt()
                            windowParams.y = downY + dy.roundToInt()
                            clampToScreen()
                            updateLayout()
                        }
                        true
                    }

                    MotionEvent.ACTION_UP -> {
                        if (dragging) snapToEdge() else expand()
                        true
                    }

                    // The system can cancel a drag mid-way (e.g. a system
                    // gesture); dock anyway so the bubble is not left floating.
                    MotionEvent.ACTION_CANCEL -> {
                        if (dragging) snapToEdge()
                        true
                    }

                    else -> false
                }
            }

            setContent {
                FloatingVolumeTheme {
                    VolumeWidget(
                        audioManager = audioManager,
                        settings = widgetSettings.value,
                        isExpanded = widgetExpanded.value,
                        maxPanelHeight = maxPanelHeight.value,
                        onExpand = ::expand,
                        onCollapse = ::collapse,
                        onOpenApp = ::openApp
                    )
                }
            }
        }

    }

    private fun openApp() {
        // Close first so the panel does not sit on top of the settings screen.
        collapse()
        try {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not open the app", e)
        }
    }

    /** Adds or removes the widget window to match the user's show/hide setting. */
    private fun applyVisibility() {
        if (prefs.widgetVisible) {
            if (overlayView.isAttachedToWindow) return
            // Position before adding so the bubble does not appear at 0,0 first.
            applyDock()
            try {
                windowManager.addView(overlayView, windowParams)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add overlay view", e)
            }
        } else {
            // Reopen as the bubble, not the panel, next time it is shown.
            collapse()
            if (!overlayView.isAttachedToWindow) return
            try { windowManager.removeView(overlayView) } catch (_: Exception) { }
        }
    }

    private fun expand() {
        if (widgetExpanded.value) return
        restoreYOnShrink = false
        // Outside touches are only watched while the panel is open, so the
        // collapsed bubble is not woken up by every tap on the screen.
        windowParams.flags = windowParams.flags or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        updateLayout()
        widgetExpanded.value = true
    }

    private fun collapse() {
        if (!widgetExpanded.value) return
        windowParams.flags = windowParams.flags and
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH.inv()
        updateLayout()
        // The window keeps the panel's size until the exit animation finishes,
        // so the position is restored by the layout listener, not here.
        restoreYOnShrink = true
        widgetExpanded.value = false
    }

    /* ── Positioning helpers ───────────────────────────────────────── */

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun widgetWidth(): Int = overlayView.width.takeIf { it > 0 } ?: dp(BUBBLE_SIZE_DP)

    private fun widgetHeight(): Int = overlayView.height.takeIf { it > 0 } ?: dp(BUBBLE_SIZE_DP)

    /**
     * Screen area outside the status bar, navigation bar and display cutout,
     * so the bubble never covers the clock or blocks the home gesture.
     */
    private fun screenArea(): Rect = try {
        val metrics = windowManager.currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
        )
        val bounds = metrics.bounds
        Rect(insets.left, insets.top, bounds.width() - insets.right, bounds.height() - insets.bottom)
    } catch (e: Exception) {
        Log.w(TAG, "Window metrics unavailable; using the display size", e)
        val display = resources.displayMetrics
        Rect(0, 0, display.widthPixels, display.heightPixels)
    }

    /** Vertical range the collapsed bubble can move in. */
    private fun bubbleTravel(area: Rect): Int = (area.height() - dp(BUBBLE_SIZE_DP)).coerceAtLeast(1)

    private fun restoreSavedPosition() {
        val area = screenArea()
        dockedRight = prefs.bubbleDockedRight
        windowParams.y = area.top + (prefs.bubbleYFraction * bubbleTravel(area)).roundToInt()
    }

    private fun updateMaxPanelHeight() {
        maxPanelHeight.value = (screenArea().height() / resources.displayMetrics.density).dp
    }

    private fun clampToScreen() {
        val area = screenArea()
        windowParams.x = windowParams.x.coerceIn(
            area.left, (area.right - widgetWidth()).coerceAtLeast(area.left)
        )
        windowParams.y = windowParams.y.coerceIn(
            area.top, (area.bottom - widgetHeight()).coerceAtLeast(area.top)
        )
    }

    /** Called when a drag ends: dock to whichever edge the bubble centre is nearer. */
    private fun snapToEdge() {
        val area = screenArea()
        dockedRight = windowParams.x + widgetWidth() / 2 > area.centerX()
        applyDock()
        val yFraction = (windowParams.y - area.top).toFloat() / bubbleTravel(area)
        prefs.saveBubblePosition(dockedRight, yFraction)
    }

    private fun applyDock() {
        val area = screenArea()
        val margin = dp(EDGE_MARGIN_DP)
        windowParams.x = if (dockedRight) area.right - widgetWidth() - margin else area.left + margin
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

/** Snapshot of the preferences the widget renders from. */
private data class WidgetSettings(
    val controlStyle: ControlStyle = ControlStyle.SLIDER,
    val streams: List<AudioStreamType> = AudioStreamType.DEFAULTS.toList(),
    val opacity: Float = WidgetPreferences.DEFAULT_OPACITY
) {
    companion object {
        fun from(prefs: WidgetPreferences) = WidgetSettings(
            controlStyle = prefs.controlStyle,
            streams = prefs.enabledStreams.sortedBy { it.ordinal },
            opacity = prefs.widgetOpacity
        )
    }
}

/** The panel closes after this long without a touch. */
private const val AUTO_COLLAPSE_MS = 8_000L

@Composable
private fun VolumeWidget(
    audioManager: AudioManager,
    settings: WidgetSettings,
    isExpanded: Boolean,
    maxPanelHeight: Dp,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onOpenApp: () -> Unit
) {
    Box {
        // ── Collapsed bubble ─────────────────────────────────────
        // Always composed but only visible when collapsed, so the
        // shared MutableState<Boolean> toggle is instant.
        AnimatedVisibility(
            visible = !isExpanded,
            enter = fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 0.6f),
            exit = fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.6f)
        ) {
            CollapsedBubble(opacity = settings.opacity, onExpand = onExpand)
        }

        // ── Expanded panel ───────────────────────────────────────
        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 0.7f),
            exit = fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.7f)
        ) {
            ExpandedPanel(
                audioManager = audioManager,
                streams = settings.streams,
                controlStyle = settings.controlStyle,
                maxHeight = maxPanelHeight,
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
    maxHeight: Dp,
    onCollapse: () -> Unit,
    onOpenApp: () -> Unit
) {
    // Auto-collapse timer: paused while a finger is on the panel, restarted
    // when it lifts or a volume changes (TalkBack adjusts without touches).
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var touching by remember { mutableStateOf(false) }
    LaunchedEffect(lastInteraction, touching) {
        if (touching) return@LaunchedEffect
        delay(AUTO_COLLAPSE_MS)
        onCollapse()
    }

    // Slider columns size the panel to their count; button rows need a fixed
    // width because their progress bars report a large intrinsic width.
    val contentWidth = when (controlStyle) {
        ControlStyle.SLIDER -> Modifier.width(IntrinsicSize.Max)
        ControlStyle.BUTTONS -> Modifier.width(220.dp)
    }

    // Opacity applies only to the idle bubble; the open panel stays opaque so
    // its controls are readable over any background.
    Surface(
        modifier = Modifier
            .widthIn(min = 168.dp)
            .heightIn(max = maxHeight)
            // Watches touches on the Initial pass without consuming them, so the
            // sliders and buttons inside still receive every event.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    touching = true
                    try {
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                        } while (event.changes.any { it.pressed })
                    } finally {
                        touching = false
                        lastInteraction = System.currentTimeMillis()
                    }
                }
            },
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = contentWidth.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painterResource(R.drawable.ic_volume_up),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Volume",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                PanelIconButton(
                    icon = Icons.Default.Settings,
                    contentDescription = "Open settings",
                    onClick = onOpenApp
                )
                PanelIconButton(
                    icon = Icons.Default.Close,
                    contentDescription = "Close",
                    onClick = onCollapse
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // The header has asymmetric padding to align its icon buttons with
            // the edge; re-balance it so the controls sit centred. The controls
            // scroll when they are taller than the screen (e.g. five button rows
            // in landscape) while the header stays in place.
            Box(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(end = 8.dp)
            ) {
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
            }
        }
    }
}

@Composable
private fun PanelIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(
            icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
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

@Composable
private fun MuteToggle(
    state: StreamVolumeState,
    stream: AudioStreamType,
    onInteraction: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilledTonalIconToggleButton(
        checked = state.isMuted,
        onCheckedChange = {
            onInteraction()
            state.toggleMute()
        },
        modifier = modifier,
        colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
            checkedContainerColor = MaterialTheme.colorScheme.errorContainer,
            checkedContentColor = MaterialTheme.colorScheme.onErrorContainer
        )
    ) {
        Icon(
            painterResource(muteIcon(state.isMuted)),
            // Toggle semantics announce on/off, so the label names the action.
            contentDescription = "Mute ${stream.label}",
            modifier = Modifier.size(18.dp)
        )
    }
}

/* ── Slider-mode controls ──────────────────────────────────────────── */

@Composable
private fun SliderControls(
    audioManager: AudioManager,
    streams: List<AudioStreamType>,
    onInteraction: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        modifier = Modifier.width(48.dp)
    ) {
        Text(
            text = "${state.percent}%",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(6.dp))

        PillSlider(
            value = state.volume,
            maxValue = state.maxVolume,
            onValueChange = { v ->
                if (v != state.volume) {
                    state.set(v)
                    onInteraction()
                }
            },
            label = "${stream.label} volume",
            icon = stream.icon,
            modifier = Modifier.size(width = 44.dp, height = 140.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        MuteToggle(
            state = state,
            stream = stream,
            onInteraction = onInteraction,
            modifier = Modifier.size(36.dp)
        )

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = stream.shortLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
    val levelColor = if (state.isMuted) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.primary

    Column {
        // Stream label + percentage
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                stream.icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stream.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${state.percent}%",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = levelColor
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        LinearProgressIndicator(
            progress = { state.volume.toFloat() / state.maxVolume },
            modifier = Modifier.fillMaxWidth(),
            color = levelColor,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Control row: [Mute] [−] [+]
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val buttonModifier = Modifier
                .weight(1f)
                .height(36.dp)

            MuteToggle(
                state = state,
                stream = stream,
                onInteraction = onInteraction,
                modifier = buttonModifier
            )

            FilledTonalIconButton(
                onClick = {
                    onInteraction()
                    state.adjust(AudioManager.ADJUST_LOWER)
                },
                modifier = buttonModifier,
                enabled = state.volume > 0
            ) {
                Icon(
                    painterResource(R.drawable.ic_remove),
                    contentDescription = "${stream.label} volume down",
                    modifier = Modifier.size(18.dp)
                )
            }

            FilledTonalIconButton(
                onClick = {
                    onInteraction()
                    state.adjust(AudioManager.ADJUST_RAISE)
                },
                modifier = buttonModifier,
                enabled = state.volume < state.maxVolume
            ) {
                Icon(
                    painterResource(R.drawable.ic_add),
                    contentDescription = "${stream.label} volume up",
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/* ── Pill slider ───────────────────────────────────────────────────── */

/**
 * Thick vertical slider in the style of the Android 12+ system volume panel.
 * The level jumps to the touch point on press and follows the finger while
 * dragging. Slider semantics let TalkBack read and adjust it like a standard slider.
 */
@Composable
private fun PillSlider(
    value: Int,
    maxValue: Int,
    onValueChange: (Int) -> Unit,
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    // The gesture coroutine outlives recompositions, so read the latest
    // callback and range through updated state rather than capturing them.
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentMax by rememberUpdatedState(maxValue)

    val fraction by animateFloatAsState(
        targetValue = value.toFloat() / maxValue,
        animationSpec = tween(100),
        label = "pillFill"
    )
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val fillColor = MaterialTheme.colorScheme.primary
    // Icon sits in the bottom ~25% of the pill; switch its tint once the fill covers it.
    val iconTint = if (fraction > 0.22f) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        contentAlignment = Alignment.BottomCenter,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(trackColor)
            .drawBehind {
                val fillHeight = size.height * fraction
                drawRect(
                    color = fillColor,
                    topLeft = Offset(0f, size.height - fillHeight),
                    size = Size(size.width, fillHeight)
                )
            }
            .pointerInput(Unit) {
                fun valueAt(y: Float): Int =
                    ((1f - y / size.height) * currentMax).roundToInt().coerceIn(0, currentMax)

                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    currentOnValueChange(valueAt(down.position.y))
                    verticalDrag(down.id) { change ->
                        change.consume()
                        currentOnValueChange(valueAt(change.position.y))
                    }
                }
            }
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = value.toFloat(),
                    range = 0f..maxValue.toFloat(),
                    steps = (maxValue - 1).coerceAtLeast(0)
                )
                setProgress { target ->
                    currentOnValueChange(target.roundToInt().coerceIn(0, currentMax))
                    true
                }
            }
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier
                .padding(bottom = 12.dp)
                .size(20.dp)
        )
    }
}
