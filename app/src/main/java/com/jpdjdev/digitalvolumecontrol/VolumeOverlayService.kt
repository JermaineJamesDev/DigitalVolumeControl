package com.jpdjdev.digitalvolumecontrol

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlin.math.abs

class VolumeOverlayService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

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
    private lateinit var lifecycleRegistry: LifecycleRegistry
    private lateinit var savedStateRegistryController: SavedStateRegistryController
    private val _viewModelStore = ViewModelStore()
    private var volumeUpdateJob: Job? = null

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = _viewModelStore

    override fun onCreate() {
        super.onCreate()
        android.util.Log.d("VolumeOverlayService", "Service onCreate")
        isRunning = true

        lifecycleRegistry = LifecycleRegistry(this)
        savedStateRegistryController = SavedStateRegistryController.create(this)
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        setupOverlayView()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("VolumeOverlayService", "onStartCommand: ${intent?.action}")

        if (intent?.action == "STOP_SERVICE") {
            android.util.Log.d("VolumeOverlayService", "Received STOP_SERVICE action")
            stopSelf()
            return START_NOT_STICKY
        }

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
        windowParams = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 200
        }

        overlayView = ComposeView(this).apply {
            setupViewTreeOwners()

            // Simple touch handling
            var startX = 0f
            var startY = 0f
            var isDragging = false
            var hasMoved = false

            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        isDragging = true
                        hasMoved = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (isDragging) {
                            val deltaX = event.rawX - startX
                            val deltaY = event.rawY - startY

                            if (abs(deltaX) > 10 || abs(deltaY) > 10) {
                                hasMoved = true
                                windowParams.x += deltaX.toInt()
                                windowParams.y += deltaY.toInt()

                                // Keep within screen bounds using modern API
                                val windowMetrics = windowManager.currentWindowMetrics
                                val bounds = windowMetrics.bounds
                                val screenWidth = bounds.width()
                                val screenHeight = bounds.height()

                                windowParams.x = windowParams.x.coerceIn(0, screenWidth - 200)
                                windowParams.y = windowParams.y.coerceIn(0, screenHeight - 200)

                                try {
                                    windowManager.updateViewLayout(this, windowParams)
                                } catch (e: Exception) {
                                    android.util.Log.e("VolumeOverlayService", "Error updating layout", e)
                                }

                                startX = event.rawX
                                startY = event.rawY
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isDragging && hasMoved) {
                            // Snap to sides using modern API
                            val windowMetrics = windowManager.currentWindowMetrics
                            val bounds = windowMetrics.bounds
                            val screenWidth = bounds.width()
                            val snapX = if (windowParams.x < screenWidth / 2) {
                                16 // Left side
                            } else {
                                screenWidth - 200 // Right side
                            }
                            windowParams.x = snapX
                            try {
                                windowManager.updateViewLayout(this, windowParams)
                            } catch (e: Exception) {
                                android.util.Log.e("VolumeOverlayService", "Error snapping", e)
                            }
                        }
                        isDragging = false
                        !hasMoved // Return false if we moved (don't trigger click)
                    }
                    else -> false
                }
            }

            setContent {
                FloatingVolumeTheme {
                    SimpleVolumeWidget(
                        audioManager = audioManager,
                        onOpenMainApp = {
                            val intent = Intent(this@VolumeOverlayService, MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            }
                            startActivity(intent)
                        }
                    )
                }
            }
        }

        try {
            windowManager.addView(overlayView, windowParams)
            android.util.Log.d("VolumeOverlayService", "Simple overlay view added successfully")
        } catch (e: Exception) {
            android.util.Log.e("VolumeOverlayService", "Failed to add overlay view", e)
            stopSelf()
        }
    }

    override fun onDestroy() {
        android.util.Log.d("VolumeOverlayService", "Service onDestroy")

        // Cancel any running jobs
        volumeUpdateJob?.cancel()

        // Mark as not running first
        isRunning = false

        // Clean up lifecycle
        try {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        } catch (e: Exception) {
            android.util.Log.w("VolumeOverlayService", "Error in lifecycle cleanup", e)
        }

        // Clear view model store
        try {
            _viewModelStore.clear()
        } catch (e: Exception) {
            android.util.Log.w("VolumeOverlayService", "Error clearing view model store", e)
        }

        // Remove overlay view
        if (::overlayView.isInitialized && ::windowManager.isInitialized) {
            try {
                windowManager.removeView(overlayView)
                android.util.Log.d("VolumeOverlayService", "Overlay view removed successfully")
            } catch (e: Exception) {
                android.util.Log.e("VolumeOverlayService", "Error removing overlay view", e)
            }
        }

        super.onDestroy()
    }

    private fun ComposeView.setupViewTreeOwners() {
        try {
            val lifecycleOwnerClass = Class.forName("androidx.lifecycle.ViewTreeLifecycleOwner")
            val setLifecycleMethod = lifecycleOwnerClass.getMethod("set", android.view.View::class.java, LifecycleOwner::class.java)
            setLifecycleMethod.invoke(null, this, this@VolumeOverlayService)
        } catch (e: Exception) {
            android.util.Log.w("VolumeOverlayService", "Failed to set ViewTreeLifecycleOwner", e)
        }

        try {
            val viewModelStoreOwnerClass = Class.forName("androidx.lifecycle.ViewTreeViewModelStoreOwner")
            val setViewModelMethod = viewModelStoreOwnerClass.getMethod("set", android.view.View::class.java, ViewModelStoreOwner::class.java)
            setViewModelMethod.invoke(null, this, this@VolumeOverlayService)
        } catch (e: Exception) {
            android.util.Log.w("VolumeOverlayService", "Failed to set ViewTreeViewModelStoreOwner", e)
        }

        try {
            val savedStateRegistryOwnerClass = Class.forName("androidx.savedstate.ViewTreeSavedStateRegistryOwner")
            val setSavedStateMethod = savedStateRegistryOwnerClass.getMethod("set", android.view.View::class.java, SavedStateRegistryOwner::class.java)
            setSavedStateMethod.invoke(null, this, this@VolumeOverlayService)
        } catch (e: Exception) {
            android.util.Log.w("VolumeOverlayService", "Failed to set ViewTreeSavedStateRegistryOwner", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

@Composable
fun SimpleVolumeWidget(
    audioManager: AudioManager,
    onOpenMainApp: () -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }
    var currentVolume by remember { mutableIntStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    var isMuted by remember { mutableStateOf(currentVolume == 0) }

    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }

    // Update volume when expanded
    LaunchedEffect(isExpanded) {
        if (isExpanded) {
            while (isExpanded) {
                try {
                    val volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    if (volume != currentVolume) {
                        currentVolume = volume
                        isMuted = volume == 0
                    }
                    delay(500) // Less frequent updates
                } catch (e: Exception) {
                    break
                }
            }
        }
    }

    // Auto-collapse after 8 seconds
    LaunchedEffect(isExpanded) {
        if (isExpanded) {
            delay(8000)
            isExpanded = false
        }
    }

    if (isExpanded) {
        Card(
            modifier = Modifier
                .width(200.dp)
                .shadow(8.dp, RoundedCornerShape(16.dp))
                .clickable { onOpenMainApp() },
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${((currentVolume.toFloat() / maxVolume) * 100).toInt()}%",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    IconButton(onClick = { isExpanded = false }) {
                        Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(18.dp))
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                LinearProgressIndicator(
                    progress = { currentVolume.toFloat() / maxVolume },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = if (isMuted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    FilledTonalButton(
                        onClick = {
                            try {
                                if (isMuted || currentVolume == 0) {
                                    val newVolume = (maxVolume * 0.5f).toInt().coerceAtLeast(1)
                                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
                                    currentVolume = newVolume
                                    isMuted = false
                                } else {
                                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                                    currentVolume = 0
                                    isMuted = true
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("VolumeWidget", "Error toggling mute", e)
                            }
                        },
                        modifier = Modifier.size(40.dp),
                        shape = CircleShape,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(
                            if (isMuted || currentVolume == 0) Icons.AutoMirrored.Filled.VolumeOff
                            else Icons.AutoMirrored.Filled.VolumeMute,
                            contentDescription = "Mute",
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    FilledTonalButton(
                        onClick = {
                            try {
                                audioManager.adjustStreamVolume(
                                    AudioManager.STREAM_MUSIC,
                                    AudioManager.ADJUST_LOWER,
                                    AudioManager.FLAG_SHOW_UI
                                )
                                currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                isMuted = currentVolume == 0
                            } catch (e: Exception) {
                                android.util.Log.e("VolumeWidget", "Error decreasing volume", e)
                            }
                        },
                        modifier = Modifier.size(40.dp),
                        shape = CircleShape,
                        enabled = currentVolume > 0,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.VolumeDown,
                            contentDescription = "Volume Down",
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    FilledTonalButton(
                        onClick = {
                            try {
                                audioManager.adjustStreamVolume(
                                    AudioManager.STREAM_MUSIC,
                                    AudioManager.ADJUST_RAISE,
                                    AudioManager.FLAG_SHOW_UI
                                )
                                currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                isMuted = false
                            } catch (e: Exception) {
                                android.util.Log.e("VolumeWidget", "Error increasing volume", e)
                            }
                        },
                        modifier = Modifier.size(40.dp),
                        shape = CircleShape,
                        enabled = currentVolume < maxVolume,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "Volume Up",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Tap here to open app",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    } else {
        Card(
            modifier = Modifier
                .size(56.dp)
                .shadow(6.dp, CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = rememberRipple(bounded = true, radius = 28.dp)
                ) {
                    isExpanded = true
                },
            shape = CircleShape,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = "Volume Control",
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}