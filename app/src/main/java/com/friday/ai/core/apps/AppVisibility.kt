package com.friday.ai.core.apps

import java.util.concurrent.atomic.AtomicInteger

/**
 * Whether Friday has a window on screen right now — what Android 10+ looks
 * at before letting an app start another one from the background.
 *
 * Declaring SYSTEM_ALERT_WINDOW proves nothing: the overlay counts only
 * while its view is attached and shown, and an activity only between
 * onResume and onPause. Both report here.
 */
object AppVisibility {

    private val resumedActivities = AtomicInteger()

    @Volatile private var overlayShown = false

    fun activityResumed() {
        resumedActivities.incrementAndGet()
    }

    fun activityPaused() {
        resumedActivities.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    fun overlay(shown: Boolean) {
        overlayShown = shown
    }

    /** [overlayAllowed]: the overlay permission is granted now, not merely declared. */
    fun visible(overlayAllowed: Boolean): Boolean =
        resumedActivities.get() > 0 || (overlayAllowed && overlayShown)
}
