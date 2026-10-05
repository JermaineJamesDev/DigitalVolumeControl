package com.jpdjdev.digitalvolumecontrol.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.jpdjdev.digitalvolumecontrol.AudioStreamType
import com.jpdjdev.digitalvolumecontrol.R

/**
 * Icon shown for a stream in the settings screen and the widget. Media and
 * alarm come from local drawables because they are not in material-icons-core.
 */
val AudioStreamType.icon: ImageVector
    @Composable get() = when (this) {
        AudioStreamType.MEDIA -> ImageVector.vectorResource(R.drawable.ic_music_note)
        AudioStreamType.RING -> Icons.Filled.Call
        AudioStreamType.NOTIFICATION -> Icons.Filled.Notifications
        AudioStreamType.ALARM -> ImageVector.vectorResource(R.drawable.ic_alarm)
        AudioStreamType.SYSTEM -> Icons.Filled.Settings
    }
