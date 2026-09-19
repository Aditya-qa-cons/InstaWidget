package com.instawidget.dm

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context

/**
 * Adds the widget to the home screen without going through the launcher's
 * widget picker.
 *
 * Some OEM launchers -- Xiaomi's is the one that prompted this -- build their
 * own widget picker and simply never list third-party providers, which leaves
 * a perfectly well-formed widget unreachable. `requestPinAppWidget` asks the
 * launcher to place it directly, which is a separate code path that those
 * launchers do implement.
 */
object WidgetPinner {

    /** Outcome of a pin attempt, so the caller can say something useful. */
    enum class Result {
        /** The launcher was asked; it shows its own confirmation dialog. */
        REQUESTED,

        /** The launcher does not support pinning. Nothing was shown. */
        UNSUPPORTED,

        /** The request was rejected outright. */
        FAILED
    }

    fun pin(context: Context): Result {
        // getInstance is the documented way in; it returns null only on
        // devices with no app widget support at all.
        val manager = AppWidgetManager.getInstance(context) ?: return Result.UNSUPPORTED
        if (!manager.isRequestPinAppWidgetSupported) return Result.UNSUPPORTED

        val provider = ComponentName(context, DmWidgetProvider::class.java)
        return if (manager.requestPinAppWidget(provider, null, null)) {
            Result.REQUESTED
        } else {
            Result.FAILED
        }
    }

    /**
     * Whether the framework has registered our provider at all.
     *
     * This is the useful diagnostic when the widget is missing from a picker:
     * if the platform lists it, the app and its manifest are fine and the
     * launcher is the thing hiding it.
     */
    fun isProviderRegistered(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context) ?: return false
        return manager.installedProviders.any {
            it.provider.packageName == context.packageName
        }
    }
}
