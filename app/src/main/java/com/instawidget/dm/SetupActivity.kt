package com.instawidget.dm

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

/**
 * One screen: what the app does, whether notification access is on, and a
 * button that opens the Settings page where the user turns it on.
 *
 * Notification access cannot be granted programmatically, so this screen is the
 * whole onboarding flow.
 */
class SetupActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var grantButton: Button
    private lateinit var widgetStatusView: TextView
    private lateinit var diagnosticsView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        // Explicit casts rather than the generic findViewById<T>: that
        // overload only exists from API 26, and this source is also built
        // against older platform jars by tools/build-apk-offline.sh.
        statusView = findViewById(R.id.setup_status) as TextView
        grantButton = findViewById(R.id.setup_grant_button) as Button
        widgetStatusView = findViewById(R.id.setup_widget_status) as TextView
        diagnosticsView = findViewById(R.id.setup_diagnostics) as TextView

        grantButton.setOnClickListener { openNotificationAccessSettings() }
        (findViewById(R.id.setup_restricted_button) as View)
            .setOnClickListener { openAppInfo() }
        (findViewById(R.id.setup_add_widget_button) as View)
            .setOnClickListener { pinWidget() }
        (findViewById(R.id.setup_copy_diagnostics_button) as View)
            .setOnClickListener { copyDiagnostics() }
        (findViewById(R.id.setup_clear_button) as View).setOnClickListener { clearCache() }
    }

    override fun onResume() {
        super.onResume()
        // Re-check on every resume: the user typically comes back here straight
        // from the Settings screen.
        render(Instagram.isNotificationAccessGranted(this))
        renderWidgetStatus()
        diagnosticsView.text = DmDiagnostics.report(this)
    }

    private fun copyDiagnostics() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            Toast.makeText(this, R.string.toast_copy_failed, Toast.LENGTH_SHORT).show()
            return
        }
        clipboard.setPrimaryClip(
            ClipData.newPlainText(getString(R.string.app_name), DmDiagnostics.report(this))
        )
        Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show()
    }

    /**
     * Reports whether the framework knows about the widget provider. If it
     * does and the launcher still will not list it, the launcher is at fault
     * and "Add widget to home screen" is the way around it.
     */
    private fun renderWidgetStatus() {
        val registered = WidgetPinner.isProviderRegistered(this)
        widgetStatusView.setText(
            if (registered) R.string.widget_status_registered
            else R.string.widget_status_missing
        )
        widgetStatusView.setTextColor(
            getColor(if (registered) R.color.status_ok else R.color.status_warn)
        )
    }

    private fun pinWidget() {
        val message = when (WidgetPinner.pin(this)) {
            WidgetPinner.Result.REQUESTED -> R.string.toast_pin_requested
            WidgetPinner.Result.UNSUPPORTED -> R.string.toast_pin_unsupported
            WidgetPinner.Result.FAILED -> R.string.toast_pin_failed
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun render(granted: Boolean) {
        if (granted) {
            statusView.setText(R.string.status_granted)
            statusView.setTextColor(getColor(R.color.status_ok))
            grantButton.setText(R.string.button_review_access)
        } else {
            statusView.setText(R.string.status_missing)
            statusView.setTextColor(getColor(R.color.status_warn))
            grantButton.setText(R.string.button_grant_access)
        }
    }

    private fun openNotificationAccessSettings() {
        try {
            startActivity(Instagram.notificationAccessSettingsIntent())
        } catch (e: ActivityNotFoundException) {
            // Some heavily skinned builds hide this screen.
            Toast.makeText(this, R.string.error_no_settings_screen, Toast.LENGTH_LONG).show()
        }
    }

    private fun openAppInfo() {
        try {
            startActivity(Instagram.appInfoIntent(this))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.error_no_settings_screen, Toast.LENGTH_LONG).show()
        }
    }

    private fun clearCache() {
        DmStore.clear(this)
        DmDiagnostics.clear(this)
        DmWidgetProvider.refreshAll(this)
        Toast.makeText(this, R.string.toast_cleared, Toast.LENGTH_SHORT).show()
    }
}
