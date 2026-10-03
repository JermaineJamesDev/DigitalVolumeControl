package com.jpdjdev.digitalvolumecontrol

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import androidx.core.content.edit

/**
 * Supported audio stream types the widget can control.
 * Each entry maps to an [AudioManager] stream constant.
 */
enum class AudioStreamType(
    val streamType: Int,
    val label: String,
    val shortLabel: String,
    val prefKey: String
) {
    MEDIA(AudioManager.STREAM_MUSIC, "Media", "Med", "stream_media"),
    RING(AudioManager.STREAM_RING, "Ring", "Ring", "stream_ring"),
    NOTIFICATION(AudioManager.STREAM_NOTIFICATION, "Notification", "Notif", "stream_notification"),
    ALARM(AudioManager.STREAM_ALARM, "Alarm", "Alrm", "stream_alarm"),
    SYSTEM(AudioManager.STREAM_SYSTEM, "System", "Sys", "stream_system");

    companion object {
        /** Default streams enabled on first launch. */
        val DEFAULTS: Set<AudioStreamType> = setOf(MEDIA)
    }
}

/** Widget control style — vertical sliders or discrete buttons. */
enum class ControlStyle(val prefValue: String, val displayName: String) {
    SLIDER("slider", "Vertical Slider"),
    BUTTONS("Buttons", "Up / Down Buttons");

    companion object {
        fun fromPref(value: String): ControlStyle =
            entries.firstOrNull { it.prefValue == value } ?: SLIDER
    }
}

/**
 * Lightweight wrapper around [SharedPreferences] for all widget settings.
 *
 * Reads are cheap (Android caches the file in memory after the first load),
 * so the overlay service can re-read on every expand without measurable cost.
 */
class WidgetPreferences(private val prefs: SharedPreferences) {

    constructor(context: Context) :
        this(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    /* ── Control style ─────────────────────────────────────────────── */

    var controlStyle: ControlStyle
        get() = ControlStyle.fromPref(
            prefs.getString(KEY_CONTROL_STYLE, ControlStyle.SLIDER.prefValue)
                ?: ControlStyle.SLIDER.prefValue
        )
        set(value) = prefs.edit { putString(KEY_CONTROL_STYLE, value.prefValue) }

    /* ── Enabled audio streams ─────────────────────────────────────── */

    var enabledStreams: Set<AudioStreamType>
        get() {
            // First launch: no key stored yet → return defaults.
            if (!prefs.contains(KEY_ENABLED_STREAMS_PREFIX + AudioStreamType.MEDIA.prefKey)) {
                return AudioStreamType.DEFAULTS
            }
            return AudioStreamType.entries.filter { stream ->
                prefs.getBoolean(KEY_ENABLED_STREAMS_PREFIX + stream.prefKey, false)
            }.toSet().ifEmpty { AudioStreamType.DEFAULTS }
        }
        set(value) {
            prefs.edit {
                AudioStreamType.entries.forEach { stream ->
                    putBoolean(
                        KEY_ENABLED_STREAMS_PREFIX + stream.prefKey,
                        stream in value
                    )
                }
            }
        }

    /** Check / toggle a single stream without replacing the whole set. */
    fun isStreamEnabled(stream: AudioStreamType): Boolean = stream in enabledStreams

    fun setStreamEnabled(stream: AudioStreamType, enabled: Boolean) {
        val current = enabledStreams.toMutableSet()
        if (enabled) current.add(stream) else current.remove(stream)
        // Always keep at least one stream active.
        if (current.isEmpty()) current.add(AudioStreamType.MEDIA)
        enabledStreams = current
    }

    /* ── Widget opacity ────────────────────────────────────────────── */

    var widgetOpacity: Float
        get() = prefs.getFloat(KEY_OPACITY, DEFAULT_OPACITY)
            .coerceIn(MIN_OPACITY, MAX_OPACITY)
        set(value) = prefs.edit {
            putFloat(KEY_OPACITY, value.coerceIn(MIN_OPACITY, MAX_OPACITY))
        }

    /* ── Constants ─────────────────────────────────────────────────── */

    companion object {
        private const val PREFS_NAME = "widget_prefs"
        private const val KEY_CONTROL_STYLE = "control_style"
        private const val KEY_ENABLED_STREAMS_PREFIX = "enabled_"
        private const val KEY_OPACITY = "widget_opacity"

        const val DEFAULT_OPACITY = 0.85f
        const val MIN_OPACITY = 0.25f
        const val MAX_OPACITY = 1.0f
    }
}