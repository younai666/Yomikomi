package io.github.yomikomi.capture

internal enum class CaptureCancelReason(val key: String) {
    TILE("tile"), APP_FOREGROUND("app_foreground"), APP_SWITCH("app_switch"),
    WINDOW_CHANGED("window_changed"), PAGE_CHANGED("page_changed"), CONFIG_CHANGED("config_changed"),
    ROTATION("rotation"), INTERRUPTED("interrupted"), SERVICE_DESTROYED("service_destroyed"),
}

/** Only confirmed activity windows may replace the target; IME/dialog events are not app switches. */
internal fun windowCancellation(targetPackage: String, targetWindow: Int, eventPackage: String,
    eventWindow: Int, activityWindow: Boolean, busy: Boolean = false): CaptureCancelReason? = when {
    !activityWindow || targetPackage.isEmpty() -> null
    eventPackage != targetPackage -> CaptureCancelReason.APP_SWITCH
    !busy && targetWindow != -1 && eventWindow != targetWindow -> CaptureCancelReason.WINDOW_CHANGED
    else -> null
}
