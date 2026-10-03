package com.jpdjdev.digitalvolumecontrol

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class WidgetPreferencesTest {

    private lateinit var prefs: WidgetPreferences

    @Before
    fun setUp() {
        prefs = WidgetPreferences(InMemorySharedPreferences())
    }

    @Test
    fun defaultsOnFirstLaunch() {
        assertEquals(ControlStyle.SLIDER, prefs.controlStyle)
        assertEquals(AudioStreamType.DEFAULTS, prefs.enabledStreams)
        assertEquals(WidgetPreferences.DEFAULT_OPACITY, prefs.widgetOpacity)
    }

    @Test
    fun controlStyleRoundTrips() {
        prefs.controlStyle = ControlStyle.BUTTONS
        assertEquals(ControlStyle.BUTTONS, prefs.controlStyle)
    }

    @Test
    fun unknownControlStyleFallsBackToSlider() {
        assertEquals(ControlStyle.SLIDER, ControlStyle.fromPref("not-a-style"))
    }

    @Test
    fun enabledStreamsRoundTrip() {
        val streams = setOf(AudioStreamType.RING, AudioStreamType.ALARM)
        prefs.enabledStreams = streams
        assertEquals(streams, prefs.enabledStreams)
    }

    @Test
    fun emptyStreamSetFallsBackToDefaults() {
        prefs.enabledStreams = emptySet()
        assertEquals(AudioStreamType.DEFAULTS, prefs.enabledStreams)
    }

    @Test
    fun cannotDisableLastStream() {
        prefs.enabledStreams = setOf(AudioStreamType.RING)
        prefs.setStreamEnabled(AudioStreamType.RING, false)
        assertEquals(setOf(AudioStreamType.MEDIA), prefs.enabledStreams)
    }

    @Test
    fun opacityIsClamped() {
        prefs.widgetOpacity = 0f
        assertEquals(WidgetPreferences.MIN_OPACITY, prefs.widgetOpacity)
        prefs.widgetOpacity = 2f
        assertEquals(WidgetPreferences.MAX_OPACITY, prefs.widgetOpacity)
    }
}

/** Minimal in-memory [SharedPreferences] for JVM unit tests. */
private class InMemorySharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()

    override fun getAll(): Map<String, *> = values.toMap()
    override fun getString(key: String, defValue: String?) = values[key] as String? ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?) =
        values[key] as Set<String>? ?: defValues
    override fun getInt(key: String, defValue: Int) = values[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long) = values[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float) = values[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = values[key] as Boolean? ?: defValue
    override fun contains(key: String) = key in values
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private var clear = false

        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) = apply { pending[key] = values }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { pending[key] = null }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            if (clear) values.clear()
            pending.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }
            return true
        }
        override fun apply() {
            commit()
        }
    }
}
