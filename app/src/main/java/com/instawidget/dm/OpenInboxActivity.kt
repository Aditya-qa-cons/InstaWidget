package com.instawidget.dm

import android.app.Activity
import android.content.ActivityNotFoundException
import android.os.Bundle
import android.widget.Toast

/**
 * Invisible step between tapping the widget and Instagram opening.
 *
 * It exists so the widget can tell when the user actually went to read their
 * DMs, which is the only honest moment to drop the "3 new messages" counts.
 * A broadcast receiver could not do this: starting an activity from the
 * background is restricted, whereas this *is* the foreground activity for the
 * instant it lives.
 *
 * Declared with Theme.NoDisplay and finished inside onCreate, so nothing is
 * ever drawn and the user only sees Instagram.
 */
class OpenInboxActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Open Instagram first. Clearing counts is the nice-to-have; landing
        // in the inbox is the thing the user asked for, so it must not be
        // able to fail because of bookkeeping.
        var opened = true
        try {
            startActivity(Instagram.inboxIntent(this))
        } catch (e: ActivityNotFoundException) {
            opened = false
            Toast.makeText(this, R.string.toast_link_unavailable, Toast.LENGTH_LONG).show()
        }

        if (opened) {
            try {
                if (DmStore.resetCounts(this)) DmWidgetProvider.refreshAll(this)
            } catch (e: Exception) {
                // Never let bookkeeping surface as a broken tap.
            }
        }

        // Theme.NoDisplay requires finishing before the activity would resume.
        finish()
    }
}
