package com.instawidget.dm

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * The whole setup flow, as a checklist that re-evaluates itself every time the
 * screen is shown.
 *
 * Notification access cannot be granted programmatically, so some of this is
 * unavoidably manual. Everything that *can* be automatic is: the inbox link is
 * chosen on first run, and a lost listener binding is repaired on resume
 * rather than being reported as a problem for the user to solve.
 */
class SetupActivity : Activity() {

    private lateinit var accessStatus: TextView
    private lateinit var accessHint: TextView
    private lateinit var accessButton: Button
    private lateinit var restrictedButton: Button

    private lateinit var readerStatus: TextView
    private lateinit var readerHint: TextView
    private lateinit var readerButton: Button

    private lateinit var widgetStatus: TextView
    private lateinit var widgetHint: TextView
    private lateinit var widgetButton: Button

    private lateinit var linkStatus: TextView
    private lateinit var linksToggle: Button
    private lateinit var threadsToggle: Button
    private lateinit var linksContainer: LinearLayout

    private lateinit var diagnosticsView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        // Explicit casts rather than the generic findViewById<T>: that
        // overload only exists from API 26, and this source is also built
        // against older platform jars by tools/build-apk-offline.sh.
        accessStatus = findViewById(R.id.step_access_status) as TextView
        accessHint = findViewById(R.id.step_access_hint) as TextView
        accessButton = findViewById(R.id.step_access_button) as Button
        restrictedButton = findViewById(R.id.step_restricted_button) as Button

        readerStatus = findViewById(R.id.step_reader_status) as TextView
        readerHint = findViewById(R.id.step_reader_hint) as TextView
        readerButton = findViewById(R.id.step_reader_button) as Button

        widgetStatus = findViewById(R.id.step_widget_status) as TextView
        widgetHint = findViewById(R.id.step_widget_hint) as TextView
        widgetButton = findViewById(R.id.step_widget_button) as Button

        linkStatus = findViewById(R.id.step_link_status) as TextView
        linksToggle = findViewById(R.id.setup_links_toggle) as Button
        threadsToggle = findViewById(R.id.setup_threads_toggle) as Button
        linksContainer = findViewById(R.id.setup_links_container) as LinearLayout

        diagnosticsView = findViewById(R.id.setup_diagnostics) as TextView

        accessButton.setOnClickListener { open(Instagram.notificationAccessSettingsIntent()) }
        restrictedButton.setOnClickListener { open(Instagram.appInfoIntent(this)) }
        readerButton.setOnClickListener { reconnectListener() }
        widgetButton.setOnClickListener { pinWidget() }
        linksToggle.setOnClickListener { toggleLinks() }
        threadsToggle.setOnClickListener { toggleThreads() }

        (findViewById(R.id.setup_copy_diagnostics_button) as View)
            .setOnClickListener { copyDiagnostics() }
        (findViewById(R.id.setup_clear_button) as View)
            .setOnClickListener { clearCache() }
    }

    override fun onResume() {
        super.onResume()

        // Pick a working inbox link without asking, so a fresh install has
        // nothing to configure.
        if (Instagram.selectedLinkId(this) == null) {
            Instagram.INBOX_LINKS.firstOrNull { it.isAvailable(this) }
                ?.let { Instagram.selectLink(this, it.id) }
        }

        val granted = Instagram.isNotificationAccessGranted(this)

        // Access granted but the reader has never run means the binding was
        // lost, most often to an app update or an OEM battery manager. Repair
        // it rather than reporting it.
        if (granted && !DmDiagnostics.isConnected(this)) {
            ListenerControl.requestRebind(this)
        }

        renderAccessStep(granted)
        renderReaderStep(granted)
        renderWidgetStep()
        renderLinkStep()
        diagnosticsView.text = DmDiagnostics.report(this)
    }

    // --- steps --------------------------------------------------------------

    private fun renderAccessStep(granted: Boolean) {
        if (granted) {
            done(accessStatus, R.string.step_access_done)
            accessHint.visibility = View.GONE
            accessButton.setText(R.string.button_review_access)
            restrictedButton.visibility = View.GONE
        } else {
            todo(accessStatus, R.string.step_access_todo)
            accessHint.visibility = View.VISIBLE
            accessHint.setText(R.string.step_access_hint)
            accessButton.setText(R.string.button_grant_access)
            // Only worth mentioning while the toggle is still off, since this
            // is what blocks it on a sideloaded build.
            restrictedButton.visibility = View.VISIBLE
        }
    }

    private fun renderReaderStep(granted: Boolean) {
        val connected = DmDiagnostics.isConnected(this)
        if (connected) {
            done(readerStatus, R.string.step_reader_done)
            readerHint.visibility = View.GONE
            readerButton.visibility = View.GONE
        } else {
            todo(readerStatus, R.string.step_reader_todo)
            readerHint.visibility = View.VISIBLE
            readerHint.setText(
                if (granted) R.string.step_reader_hint else R.string.step_reader_blocked
            )
            readerButton.visibility = if (granted) View.VISIBLE else View.GONE
        }
    }

    private fun renderWidgetStep() {
        if (WidgetPinner.isWidgetPlaced(this)) {
            done(widgetStatus, R.string.step_widget_done)
            widgetHint.visibility = View.GONE
            widgetButton.setText(R.string.button_add_another_widget)
        } else {
            todo(widgetStatus, R.string.step_widget_todo)
            widgetHint.visibility = View.VISIBLE
            widgetHint.setText(R.string.step_widget_hint)
            widgetButton.setText(R.string.button_add_widget)
        }
    }

    private fun renderLinkStep() {
        val selected = Instagram.selectedLinkId(this)
        val label = Instagram.INBOX_LINKS.firstOrNull { it.id == selected }?.label
        if (label != null) {
            done(linkStatus, getString(R.string.step_link_done, label))
        } else {
            todo(linkStatus, getString(R.string.step_link_todo))
        }
        renderInboxLinks()
        renderThreadsToggle()
    }

    private fun renderThreadsToggle() {
        threadsToggle.setText(
            if (Instagram.openThreadsEnabled(this)) R.string.button_open_threads_on
            else R.string.button_open_threads_off
        )
    }

    private fun toggleThreads() {
        val enabled = !Instagram.openThreadsEnabled(this)
        Instagram.setOpenThreads(this, enabled)
        renderThreadsToggle()
        Toast.makeText(
            this,
            if (enabled) R.string.toast_threads_on else R.string.toast_threads_off,
            Toast.LENGTH_LONG
        ).show()
    }

    private fun done(view: TextView, resId: Int) = done(view, getString(resId))

    private fun done(view: TextView, text: String) {
        view.text = getString(R.string.step_done_prefix, text)
        view.setTextColor(getColor(R.color.status_ok))
    }

    private fun todo(view: TextView, resId: Int) = todo(view, getString(resId))

    private fun todo(view: TextView, text: String) {
        view.text = getString(R.string.step_todo_prefix, text)
        view.setTextColor(getColor(R.color.status_warn))
    }

    // --- actions ------------------------------------------------------------

    private fun open(intent: android.content.Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.error_no_settings_screen, Toast.LENGTH_LONG).show()
        }
    }

    private fun reconnectListener() {
        val ok = ListenerControl.requestRebind(this)
        Toast.makeText(
            this,
            if (ok) R.string.toast_rebind_requested else R.string.toast_rebind_failed,
            Toast.LENGTH_LONG
        ).show()
        diagnosticsView.text = DmDiagnostics.report(this)
    }

    private fun pinWidget() {
        val message = when (WidgetPinner.pin(this)) {
            WidgetPinner.Result.REQUESTED -> R.string.toast_pin_requested
            WidgetPinner.Result.UNSUPPORTED -> R.string.toast_pin_unsupported
            WidgetPinner.Result.FAILED -> R.string.toast_pin_failed
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun toggleLinks() {
        val showing = linksContainer.visibility == View.VISIBLE
        linksContainer.visibility = if (showing) View.GONE else View.VISIBLE
        linksToggle.setText(if (showing) R.string.button_show_links else R.string.button_hide_links)
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

    private fun clearCache() {
        DmStore.clear(this)
        DmDiagnostics.clear(this)
        DmWidgetProvider.refreshAll(this)
        Toast.makeText(this, R.string.toast_cleared, Toast.LENGTH_SHORT).show()
    }

    // --- inbox link chooser -------------------------------------------------

    private fun renderInboxLinks() {
        linksContainer.removeAllViews()
        val selected = Instagram.selectedLinkId(this)
        for (link in Instagram.INBOX_LINKS) {
            linksContainer.addView(buildLinkRow(link, link.id == selected))
        }
    }

    private fun buildLinkRow(link: Instagram.InboxLink, isSelected: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }

        val available = link.isAvailable(this)
        row.addView(TextView(this).apply {
            text = if (isSelected) getString(R.string.link_selected, link.label) else link.label
            textSize = 13f
            setTextColor(
                getColor(
                    when {
                        isSelected -> R.color.brand_purple
                        available -> R.color.text_primary
                        else -> R.color.text_secondary
                    }
                )
            )
            // Links this phone cannot open are dimmed rather than hidden, so
            // the list still reads as the full set of things to try.
            alpha = if (available) 1f else 0.45f
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        row.addView(smallButton(R.string.button_try_link) { tryLink(link) })
        row.addView(smallButton(R.string.button_use_link) { useLink(link) })
        return row
    }

    private fun smallButton(textRes: Int, onClick: () -> Unit): Button =
        Button(this).apply {
            setText(textRes)
            textSize = 13f
            isAllCaps = false
            setTextColor(getColor(R.color.brand_purple))
            setBackgroundResource(R.drawable.btn_secondary)
            minimumWidth = dp(64)
            minimumHeight = dp(40)
            stateListAnimator = null
            setPadding(dp(12), 0, dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                leftMargin = dp(6)
            }
            setOnClickListener { onClick() }
        }

    private fun tryLink(link: Instagram.InboxLink) {
        try {
            startActivity(link.intent())
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.toast_link_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun useLink(link: Instagram.InboxLink) {
        Instagram.selectLink(this, link.id)
        DmWidgetProvider.refreshAll(this)
        renderLinkStep()
        Toast.makeText(this, getString(R.string.toast_link_selected, link.label), Toast.LENGTH_SHORT)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
