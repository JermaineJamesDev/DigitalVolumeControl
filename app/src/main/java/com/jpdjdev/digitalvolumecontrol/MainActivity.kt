package com.jpdjdev.digitalvolumecontrol

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.jpdjdev.digitalvolumecontrol.ui.FloatingVolumeTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        // Handle notification permission result if needed
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request notification permission if needed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            FloatingVolumeTheme {
                MainScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh the UI when returning to the app (e.g., from permission settings)
        setContent {
            FloatingVolumeTheme {
                MainScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    var hasOverlayPermission by remember { mutableStateOf(false) }
    var isServiceRunning by remember { mutableStateOf(false) }
    var isCheckingPermission by remember { mutableStateOf(false) }

    // Check permissions and service status - reduced frequency for better performance
    LaunchedEffect(Unit) {
        while (true) {
            hasOverlayPermission = Settings.canDrawOverlays(context)
            isServiceRunning = VolumeOverlayService.isRunning
            delay(1000) // Check every 1 second instead of 500ms
        }
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
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            PermissionStatusCard(
                hasOverlayPermission = hasOverlayPermission,
                isCheckingPermission = isCheckingPermission,
                onRequestPermission = {
                    isCheckingPermission = true
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                    context.startActivity(intent)
                },
                onPermissionResult = { granted ->
                    hasOverlayPermission = granted
                    isCheckingPermission = false
                }
            )

            if (hasOverlayPermission) {
                ServiceControlCard(
                    isServiceRunning = isServiceRunning,
                    onStartService = {
                        try {
                            val intent = Intent(context, VolumeOverlayService::class.java)
                            context.startForegroundService(intent)
                        } catch (e: Exception) {
                            android.util.Log.e("MainActivity", "Failed to start service", e)
                        }
                    },
                    onStopService = {
                        try {
                            val intent = Intent(context, VolumeOverlayService::class.java)
                            context.stopService(intent)
                        } catch (e: Exception) {
                            android.util.Log.e("MainActivity", "Failed to stop service", e)
                        }
                    }
                )
            }

            InstructionsCard()
            AppInfoCard()
        }
    }
}

@Composable
private fun PermissionStatusCard(
    hasOverlayPermission: Boolean,
    isCheckingPermission: Boolean,
    onRequestPermission: () -> Unit,
    onPermissionResult: (Boolean) -> Unit
) {
    val context = LocalContext.current

    // Auto-check permission status when returning from settings - optimized
    LaunchedEffect(isCheckingPermission) {
        if (isCheckingPermission) {
            delay(1500) // Wait a bit longer for user to potentially grant permission
            val hasPermission = Settings.canDrawOverlays(context)
            onPermissionResult(hasPermission)
        }
    }

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
                    Icons.Default.CheckCircle
                else Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
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
                    text = "This app needs permission to draw over other apps to show the floating volume widget",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )

                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onRequestPermission,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isCheckingPermission
                ) {
                    if (isCheckingPermission) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Checking...")
                    } else {
                        Text("Grant Overlay Permission")
                    }
                }
            }
        }
    }
}

@Composable
private fun ServiceControlCard(
    isServiceRunning: Boolean,
    onStartService: () -> Unit,
    onStopService: () -> Unit
) {
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
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isServiceRunning)
                        "Floating Widget Active"
                    else "Floating Widget Inactive",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(
                            color = if (isServiceRunning)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                            shape = CircleShape
                        )
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = {
                        try {
                            val intent = Intent(context, VolumeOverlayService::class.java)
                            context.startForegroundService(intent)
                            android.util.Log.d("MainActivity", "Started VolumeOverlayService")
                        } catch (e: Exception) {
                            android.util.Log.e("MainActivity", "Failed to start service", e)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isServiceRunning
                ) {
                    Text("Start Widget")
                }

                OutlinedButton(
                    onClick = {
                        try {
                            // Send stop action to service
                            val stopIntent = Intent(context, VolumeOverlayService::class.java).apply {
                                action = "STOP_SERVICE"
                            }
                            context.startService(stopIntent)

                            // Also stop service directly
                            val intent = Intent(context, VolumeOverlayService::class.java)
                            context.stopService(intent)

                            android.util.Log.d("MainActivity", "Stopped VolumeOverlayService")
                        } catch (e: Exception) {
                            android.util.Log.e("MainActivity", "Failed to stop service", e)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = isServiceRunning
                ) {
                    Text("Stop Widget")
                }
            }
        }
    }
}

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
                text = "How to use:",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            val instructions = listOf(
                "Tap the floating button to expand controls",
                "Use volume buttons to adjust sound",
                "Tap mute to toggle sound on/off",
                "Drag the widget to reposition",
                "Works even when phone is locked",
                "Widget stays on top of other apps"
            )

            instructions.forEach { instruction ->
                Row(
                    modifier = Modifier.padding(vertical = 2.dp)
                ) {
                    Text(
                        text = "• ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = instruction,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

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
                text = "About:",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Digital Volume Control provides a convenient floating widget for quick volume adjustments. " +
                        "The widget can be positioned anywhere on your screen and works across all apps.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

