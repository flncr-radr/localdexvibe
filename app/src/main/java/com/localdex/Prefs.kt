package com.localdex

import android.content.Context

object Prefs {
    private const val PREFS_NAME = "localdex_prefs"
    private const val KEY_HAS_PAIRED = "has_paired_before"
    private const val KEY_DISPLAY_SPEC = "display_spec"
    private const val KEY_PANEL_POSITION = "panel_position_fraction"

    const val DEFAULT_DISPLAY_SPEC = "1920x1440/240"

    /** Where the viewer's exit-tab grip sits, as a fraction of screen height up from the bottom. */
    const val DEFAULT_PANEL_POSITION_FRACTION = 1f / 3f

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasPairedBefore(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HAS_PAIRED, false)

    fun setHasPairedBefore(context: Context) {
        prefs(context).edit().putBoolean(KEY_HAS_PAIRED, true).apply()
    }

    fun getDisplaySpec(context: Context): String =
        prefs(context).getString(KEY_DISPLAY_SPEC, DEFAULT_DISPLAY_SPEC) ?: DEFAULT_DISPLAY_SPEC

    fun setDisplaySpec(context: Context, spec: String) {
        prefs(context).edit().putString(KEY_DISPLAY_SPEC, spec).apply()
    }

    fun getPanelPositionFraction(context: Context): Float =
        prefs(context).getFloat(KEY_PANEL_POSITION, DEFAULT_PANEL_POSITION_FRACTION)

    fun setPanelPositionFraction(context: Context, fraction: Float) {
        prefs(context).edit().putFloat(KEY_PANEL_POSITION, fraction).apply()
    }
}
