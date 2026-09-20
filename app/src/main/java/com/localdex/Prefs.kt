package com.localdex

import android.content.Context

object Prefs {
    private const val PREFS_NAME = "localdex_prefs"
    private const val KEY_HAS_PAIRED = "has_paired_before"
    private const val KEY_DISPLAY_SPEC = "display_spec"
    private const val KEY_PANEL_POSITION = "panel_position_fraction"
    private const val KEY_FORCE_FREEFORM = "force_freeform"
    private const val KEY_OVERLAY_DISPLAY = "use_overlay_display"
    private const val KEY_PENDING_OVERLAY_RESTORE = "pending_overlay_restore"

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

    /**
     * Whether to force the display into legacy freeform windowing
     * (`enable_freeform_support` plus `wm set-display-windowing-mode`).
     *
     * Defaults to true because that is what every build so far has shipped, but it
     * is a hack with a cost: forcing the per-display windowing mode is checked
     * *before* all desktop-mode heuristics, so it takes DeX down the legacy
     * freeform path instead of real desktop windowing. On that path the shell marks
     * every window always-on-top, and always-on-top tasks refuse to be reordered to
     * the bottom (TaskDisplayArea.positionChildTaskAt logs "Ignoring move of
     * always-on-top root task ... to bottom") — which is exactly why minimize and
     * show-desktop do nothing. Turning this off lets DeX decide for itself; whether
     * windows still float then is the whole point of the experiment.
     */
    fun getForceFreeform(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FORCE_FREEFORM, true)

    fun setForceFreeform(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FORCE_FREEFORM, enabled).apply()
    }

    /**
     * Whether to run DeX on a display created by the global `overlay_display_devices`
     * setting, mirrored by id, instead of on a VirtualDisplay this app creates.
     *
     * The difference is who makes the display. `new_display` has scrcpy create a
     * VirtualDisplay we own; `overlay_display_devices` has the system create one
     * through its own OverlayDisplayAdapter, which presents it to the rest of the
     * framework as an ordinary secondary display rather than as an app's virtual one.
     *
     * That distinction is the best remaining explanation for what this project has
     * been stuck on: on our display every plain-framework behaviour works and every
     * behaviour that needs DeX's own session state does not — its taskbar never
     * lists running apps, its circle minimizes nothing, its desktop selector opens
     * empty — which is the shape of a device that never considered this display
     * eligible for DeX in the first place. Setting dex_on_external_display=1 was not
     * enough on its own, and the container a working DeX parents its windows under
     * is attached with an API only the system shell can call, so making the display
     * itself look right is the lever we still have.
     *
     * Defaults to false: it is an experiment, it writes a global setting, and an
     * overlay display puts a preview window on the phone's own screen, which a
     * VirtualDisplay does not.
     */
    fun getUseOverlayDisplay(context: Context): Boolean =
        prefs(context).getBoolean(KEY_OVERLAY_DISPLAY, false)

    fun setUseOverlayDisplay(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_OVERLAY_DISPLAY, enabled).apply()
    }

    /**
     * What `overlay_display_devices` should be put back to, written *before* this
     * app changes it and cleared once it has been put back.
     *
     * A session normally restores the setting itself on stop. This exists for the
     * case where it never gets the chance: `overlay_display_devices` is device-wide
     * and survives the app, so a crash — the app's, or the system's — would
     * otherwise leave a phantom display on the phone for good, recreated on every
     * boot. A non-null value here means the last session died without cleaning up,
     * and the next one restores it before doing anything else.
     *
     * Written with commit() rather than apply(): the whole point is to survive a
     * process that is about to die, and apply() only promises the write eventually.
     */
    fun getPendingOverlayRestore(context: Context): String? =
        prefs(context).getString(KEY_PENDING_OVERLAY_RESTORE, null)

    fun setPendingOverlayRestore(context: Context, value: String?) {
        val editor = prefs(context).edit()
        if (value == null) editor.remove(KEY_PENDING_OVERLAY_RESTORE)
        else editor.putString(KEY_PENDING_OVERLAY_RESTORE, value)
        editor.commit()
    }

    fun getPanelPositionFraction(context: Context): Float =
        prefs(context).getFloat(KEY_PANEL_POSITION, DEFAULT_PANEL_POSITION_FRACTION)

    fun setPanelPositionFraction(context: Context, fraction: Float) {
        prefs(context).edit().putFloat(KEY_PANEL_POSITION, fraction).apply()
    }
}
