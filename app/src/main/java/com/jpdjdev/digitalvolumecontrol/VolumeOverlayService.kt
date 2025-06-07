package com.jpdjdev.digitalvolumecontrol

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.jpdjdev.digitalvolumecontrol.ui.FloatingVolumeTheme
import com.jpdjdev.digitalvolumecontrol.ui.GlassTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Service that displays a floating volume control widget.
 * Includes proper lifecycle management for ComposeView.
 */
class VolumeOverlayService : Service(), LifecycleOwner, ViewModelStoreOwner,
    SavedStateRegistryOwner {
    companion object {
        var isRunning = false
            private set
        private const val NOTIFICATION_CHANNEL_ID = "volume_overlay_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: ComposeView
    private lateinit var audioManager: AudioManager
    private lateinit var windowParams: WindowManager.LayoutParams

    // Lifecycle management
    private lateinit var lifecycleRegistry: LifecycleRegistry
    private lateinit var savedStateRegistryController: SavedStateRegistryController
    private val _viewModelStore = ViewModelStore()

    // Lifecycle interfaces
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = _viewModelStore

    override fun onCreate() {
        super.onCreate()
        isRunning = true

        // Initialize lifecycle components
        lifecycleRegistry = LifecycleRegistry(this)
        savedStateRegistryController = SavedStateRegistryController.create(this)

        // Perform lifecycle transitions
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        // Initialize system services
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Create notification channel and start foreground service
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        // Set up the overlay view
        setupOverlayView()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Volume Control Widget",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Floating volume control widget service"
            setShowBadge(false)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }.apply {
            setContentTitle("Volume Control Active")
            setContentText("Tap to manage settings")
            setSmallIcon(android.R.drawable.ic_media_play)
            setContentIntent(pendingIntent)
            setOngoing(true)
        }.build()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupOverlayView() {
        // Configure window parameters
        windowParams = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    FLAG_TURN_SCREEN_ON
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 200
        }

        // Create ComposeView
        overlayView = ComposeView(this).apply {
            // Set up ViewTree owners for Compose to work in Service context
            setupViewTreeOwners()

            setContent {
                FloatingVolumeTheme {
                    FloatingVolumeWidget(
                        audioManager = audioManager,
                        onDrag = { deltaX, deltaY ->
                            windowParams.x += deltaX.roundToInt()
                            windowParams.y += deltaY.roundToInt()
                            try {
                                windowManager.updateViewLayout(this, windowParams)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    )
                }
            }
        }

        // Add view to window
        try {
            windowManager.addView(overlayView, windowParams)
            android.util.Log.d("VolumeOverlayService", "Overlay view added successfully. Lock screen display enabled.")
        } catch (e: Exception) {
            android.util.Log.e("VolumeOverlayService", "Failed to add overlay view", e)
            stopSelf()
        }
    }

    override fun onDestroy() {
        isRunning = false

        // Handle lifecycle destruction
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)

        // Clear ViewModelStore
        _viewModelStore.clear()

        // Remove overlay view
        if (::overlayView.isInitialized) {
            try {
                windowManager.removeView(overlayView)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        super.onDestroy()
    }

    /**
     * Sets up ViewTree owners for the ComposeView to work in Service context
     */
    private fun ComposeView.setupViewTreeOwners() {
        try {
            // Set ViewTreeLifecycleOwner
            val lifecycleOwnerClass = Class.forName("androidx.lifecycle.ViewTreeLifecycleOwner")
            val setLifecycleMethod = lifecycleOwnerClass.getMethod("set", android.view.View::class.java, LifecycleOwner::class.java)
            setLifecycleMethod.invoke(null, this, this@VolumeOverlayService)
        } catch (e: Exception) {
            android.util.Log.w("VolumeOverlayService", "Failed to set ViewTreeLifecycleOwner", e)
        }

        try {
            // Set ViewTreeViewModelStoreOwner
            val viewModelStoreOwnerClass = Class.forName("androidx.lifecycle.ViewTreeViewModelStoreOwner")
            val setViewModelMethod = viewModelStoreOwnerClass.getMethod("set", android.view.View::class.java, ViewModelStoreOwner::class.java)
            setViewModelMethod.invoke(null, this, this@VolumeOverlayService)
        } catch (e: Exception) {
            android.util.Log.w("VolumeOverlayService", "Failed to set ViewTreeViewModelStoreOwner", e)
        }

        try {
            // Set ViewTreeSavedStateRegistryOwner
            val savedStateRegistryOwnerClass = Class.forName("androidx.savedstate.ViewTreeSavedStateRegistryOwner")
            val setSavedStateMethod = savedStateRegistryOwnerClass.getMethod("set", android.view.View::class.java, SavedStateRegistryOwner::class.java)
            setSavedStateMethod.invoke(null, this, this@VolumeOverlayService)
        } catch (e: Exception) {
            android.util.Log.w("VolumeOverlayService", "Failed to set ViewTreeSavedStateRegistryOwner", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Check if device is currently locked (for potential lock screen optimizations)
     */
    private fun isDeviceLocked(): Boolean {
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return keyguardManager.isKeyguardLocked
    }
}

@Composable
fun FloatingVolumeWidget(
    audioManager: AudioManager,
    onDrag: (Float, Float) -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }
    var isMuted by remember { mutableStateOf(false) }
    var currentVolume by remember {
        mutableStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    val maxVolume = remember {
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    }

    // Optimize volume updates - less frequent polling
    LaunchedEffect(isExpanded) {
        while (isExpanded) {
            val volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (volume != currentVolume) {
                currentVolume = volume
                isMuted = volume == 0
            }
            delay(200) // Update every 200ms only when expanded
        }
    }

    Box(
        modifier = Modifier
            .wrapContentSize()
            .pointerInput(Unit) {
                detectDragGestures { _, dragAmount ->
                    onDrag(dragAmount.x, dragAmount.y)
                }
            }
    ) {
        AnimatedContent(
            targetState = isExpanded,
            transitionSpec = {
                if (targetState) {
                    (slideInHorizontally(
                        animationSpec = tween(200, easing = FastOutSlowInEasing)
                    ) { width -> width } + fadeIn(tween(150))).togetherWith(
                        slideOutHorizontally(
                        animationSpec = tween(150, easing = FastOutLinearInEasing)
                    ) { width -> width } + fadeOut(tween(100)))
                } else {
                    (slideInHorizontally(
                        animationSpec = tween(150, easing = FastOutLinearInEasing)
                    ) { width -> -width } + fadeIn(tween(100))).togetherWith(
                        slideOutHorizontally(
                        animationSpec = tween(200, easing = FastOutSlowInEasing)
                    ) { width -> -width } + fadeOut(tween(150)))
                }.using(SizeTransform(clip = false))
            },
            label = "expand_animation"
        ) { expanded ->
            if (expanded) {
                ExpandedVolumeControls(
                    currentVolume = currentVolume,
                    maxVolume = maxVolume,
                    isMuted = isMuted,
                    onMuteToggle = { muted ->
                        isMuted = muted
                        if (muted) {
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                            currentVolume = 0
                        } else {
                            val newVolume = (maxVolume * 0.5f).toInt().coerceAtLeast(1)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
                            currentVolume = newVolume
                        }
                    },
                    onVolumeDown = {
                        if (currentVolume > 0) {
                            // Show system volume UI for multi-channel control
                            audioManager.adjustStreamVolume(
                                AudioManager.STREAM_MUSIC,
                                AudioManager.ADJUST_LOWER,
                                AudioManager.FLAG_SHOW_UI
                            )
                            // Update our state after a brief delay to allow system UI to show
                            CoroutineScope(Dispatchers.Main).launch {
                                delay(100)
                                currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                if (currentVolume == 0) {
                                    isMuted = true
                                }
                            }
                        }
                    },
                    onVolumeUp = {
                        // Show system volume UI for multi-channel control
                        audioManager.adjustStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            AudioManager.ADJUST_RAISE,
                            AudioManager.FLAG_SHOW_UI
                        )
                        // Update our state after a brief delay to allow system UI to show
                        CoroutineScope(Dispatchers.Main).launch {
                            delay(100)
                            currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                            if (currentVolume > 0) {
                                isMuted = false
                            }
                        }
                    },
                    onClose = { isExpanded = false }
                )
            } else {
                CollapsedVolumeButton(
                    onClick = { isExpanded = true }
                )
            }
        }
    }
}

@Composable
private fun ExpandedVolumeControls(
    currentVolume: Int,
    maxVolume: Int,
    isMuted: Boolean,
    onMuteToggle: (Boolean) -> Unit,
    onVolumeDown: () -> Unit,
    onVolumeUp: () -> Unit,
    onClose: () -> Unit
) {
    // Glassmorphism container
    Box(
        modifier = Modifier
            .width(GlassTheme.expandedWidth)
            .background(
                color = GlassTheme.glassBackground(),
                shape = RoundedCornerShape(GlassTheme.cornerRadius)
            )
            .border(
                width = 1.dp,
                color = GlassTheme.glassBorder(),
                shape = RoundedCornerShape(GlassTheme.cornerRadius)
            )
            .clip(RoundedCornerShape(GlassTheme.cornerRadius))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Compact header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${((currentVolume.toFloat() / maxVolume) * 100).toInt()}%",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
                )

                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(GlassTheme.closeButtonSize)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Compact control buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassVolumeButton(
                    icon = if (isMuted || currentVolume == 0) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeMute,
                    contentDescription = if (isMuted) "Unmute" else "Mute",
                    onClick = { onMuteToggle(!isMuted) },
                    isActive = isMuted,
                    color = if (isMuted) GlassTheme.errorGlass() else GlassTheme.secondaryGlass()
                )

                GlassVolumeButton(
                    icon = Icons.AutoMirrored.Filled.VolumeDown,
                    contentDescription = "Volume Down",
                    onClick = onVolumeDown,
                    enabled = currentVolume > 0,
                    color = GlassTheme.secondaryGlass()
                )

                GlassVolumeButton(
                    icon = Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = "Volume Up",
                    onClick = onVolumeUp,
                    enabled = currentVolume < maxVolume,
                    color = GlassTheme.secondaryGlass()
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Compact progress indicator
            LinearProgressIndicator(
                progress = { currentVolume.toFloat() / maxVolume },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(1.5.dp)),
                color = if (isMuted || currentVolume == 0)
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                else
                    GlassTheme.primaryGlass(),
                trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
            )
        }
    }
}

@Composable
private fun CollapsedVolumeButton(
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(GlassTheme.collapsedSize)
            .background(
                color = GlassTheme.primaryGlass(),
                shape = CircleShape
            )
            .border(
                width = 1.dp,
                color = GlassTheme.glassBorder(),
                shape = CircleShape
            )
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = rememberRipple(bounded = true, radius = 24.dp)
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = "Expand Volume Controls",
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f)
        )
    }
}

@Composable
fun GlassVolumeButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isActive: Boolean = false,
    color: Color = GlassTheme.secondaryGlass()
) {
    // Simplified animation - no scale effect for better performance
    val buttonColor by animateColorAsState(
        targetValue = if (isActive) color else color.copy(alpha = 0.6f),
        animationSpec = tween(150),
        label = "button_color"
    )

    Box(
        modifier = modifier
            .size(GlassTheme.buttonSize)
            .background(
                color = buttonColor.copy(alpha = if (enabled) buttonColor.alpha else 0.3f),
                shape = CircleShape
            )
            .border(
                width = 0.5.dp,
                color = GlassTheme.glassBorder().copy(alpha = if (enabled) 0.5f else 0.2f),
                shape = CircleShape
            )
            .clip(CircleShape)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(GlassTheme.iconSize),
            tint = MaterialTheme.colorScheme.onSurface.copy(
                alpha = if (enabled) 0.8f else 0.4f
            )
        )
    }
}

// Keep the old VolumeControlButton for backward compatibility, but simplified
@Composable
fun VolumeControlButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isActive: Boolean = false
) {
    GlassVolumeButton(
        icon = icon,
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        isActive = isActive
    )
}