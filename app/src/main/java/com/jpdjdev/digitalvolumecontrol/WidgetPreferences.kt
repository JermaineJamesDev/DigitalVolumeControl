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
    MEDIA(AudioManager.STREAM_MUSIC, "Media", "Media", "stream_media"),
    RING(AudioManager.STREAM_RING, "Ring", "Ring", "stream_ring"),
    NOTIFICATION(AudioManager.STREAM_NOTIFICATION, "Notification", "Notify", "stream_notification"),
    ALARM(AudioManager.STREAM_ALARM, "Alarm", "Alarm", "stream_alarm"),
    SYSTEM(AudioManager.STREAM_SYSTEM, "System", "System", "stream_system");

    companion object {
        /** Default streams enabled on first launch. */
        val DEFAULTS: Set<AudioStreamType> = setOf(MEDIA)
    }
}

/** Widget control style — vertical sliders or discrete buttons. */
enum class ControlStyle(val prefValue: String, val displayName: String) {
    SLIDER("slider", "Sliders"),
    BUTTONS("Buttons", "Buttons");

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

    /* ── Bubble visibility ─────────────────────────────────────────── */

    /**
     * Whether the bubble is shown while the accessibility service is enabled.
     * Persisted so a hidden bubble stays hidden when the service restarts.
     */
    var widgetVisible: Boolean
        get() = prefs.getBoolean(KEY_WIDGET_VISIBLE, true)
        set(value) = prefs.edit { putBoolean(KEY_WIDGET_VISIBLE, value) }

    /* ── Bubble position ───────────────────────────────────────────── */

    /** Which screen edge the bubble is docked to. */
    val bubbleDockedRight: Boolean
        get() = prefs.getBoolean(KEY_BUBBLE_DOCKED_RIGHT, false)

    /**
     * Vertical bubble position as a fraction (0 = top, 1 = bottom) of the space
     * the bubble can move in, so it lands in the same place after rotation.
     */
    val bubbleYFraction: Float
        get() = prefs.getFloat(KEY_BUBBLE_Y_FRACTION, DEFAULT_BUBBLE_Y_FRACTION).coerceIn(0f, 1f)

    fun saveBubblePosition(dockedRight: Boolean, yFraction: Float) = prefs.edit {
        putBoolean(KEY_BUBBLE_DOCKED_RIGHT, dockedRight)
        putFloat(KEY_BUBBLE_Y_FRACTION, yFraction.coerceIn(0f, 1f))
    }

    /**
     * [SharedPreferences] keeps only a weak reference to [listener], so the
     * caller must hold a strong reference until it unregisters.
     */
    fun registerChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    /* ── Constants ─────────────────────────────────────────────────── */

    companion object {
        private const val PREFS_NAME = "widget_prefs"
        private const val KEY_CONTROL_STYLE = "control_style"
        private const val KEY_ENABLED_STREAMS_PREFIX = "enabled_"
        private const val KEY_OPACITY = "widget_opacity"
        const val KEY_WIDGET_VISIBLE = "widget_visible"
        private const val KEY_BUBBLE_DOCKED_RIGHT = "bubble_docked_right"
        private const val KEY_BUBBLE_Y_FRACTION = "bubble_y_fraction"

        const val DEFAULT_OPACITY = 0.85f
        const val MIN_OPACITY = 0.25f
        const val MAX_OPACITY = 1.0f
        const val DEFAULT_BUBBLE_Y_FRACTION = 0.2f

        /** Keys the overlay writes itself on every drag; listeners can skip them. */
        fun isPositionKey(key: String?): Boolean =
            key == KEY_BUBBLE_DOCKED_RIGHT || key == KEY_BUBBLE_Y_FRACTION
    }
}