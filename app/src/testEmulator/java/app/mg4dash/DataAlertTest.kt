package app.mg4dash

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.mg4dash.system.DataUsage
import app.mg4dash.ui.DataAlert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The 4G data alert on home: shown once when waiting, never twice, and only with a reading. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1920dp-h720dp-land-mdpi")
class DataAlertTest {
    private lateinit var activity: Activity

    @Before fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        DataUsage.pendingAlert = null
        DataUsage.refreshSoon(activity)
    }

    private fun content() = activity.findViewById<ViewGroup>(android.R.id.content)
    private fun alert(): View? = content().findViewWithTag("data_alert")

    private fun texts(v: View): List<String> = when (v) {
        is TextView -> listOf(v.text.toString())
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }

    @Test fun nothingWaitingShowsNothing() {
        DataAlert.attach(activity)
        try { assertNull(alert()) } finally { DataAlert.detach(activity) }
    }

    @Test fun waitingAlertIsShownOnceAndCleared() {
        DataUsage.pendingAlert = 1
        DataAlert.attach(activity)
        try {
            val a = alert()
            assertNotNull(a)
            assertNull("shown: no longer waiting", DataUsage.pendingAlert)
            // The total and the threshold are in the text
            val all = texts(a!!).joinToString("\n")
            assertTrue(all, all.contains(DataUsage.format(activity, DataUsage.last!!.total)))
            assertTrue(all, all.contains(DataUsage.formatRound(activity, DataUsage.threshold(activity))))
            // Coming back to home with another one waiting: still a single alert on screen
            DataUsage.pendingAlert = 1
            DataAlert.attach(activity)
            assertEquals(1, (0 until content().childCount).count { content().getChildAt(it).tag == "data_alert" })
        } finally { DataAlert.detach(activity) }
    }

    @Test fun okClosesIt() {
        DataUsage.pendingAlert = 1
        DataAlert.attach(activity)
        try {
            val ok = activity.getString(android.R.string.ok)
            fun find(v: View): View? = when {
                v is TextView && v.text.toString() == ok -> v
                v is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
                else -> null
            }
            assertTrue(find(alert()!!)!!.performClick())
            assertNull(alert())
        } finally { DataAlert.detach(activity) }
    }

    @Test fun crossingWhileHomeIsInFrontShowsIt() {
        DataUsage.setThreshold(activity, 500_000_000L)
        DataAlert.attach(activity)
        try {
            assertNull(alert())
            // The simulated month is about 340 MB: over 250 MB
            DataUsage.setThreshold(activity, 250_000_000L)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertNotNull(alert())
        } finally { DataAlert.detach(activity) }
    }

    @Test fun crossingAwayFromHomeWaitsForTheReturn() {
        DataUsage.setThreshold(activity, 500_000_000L)
        DataAlert.attach(activity)
        DataAlert.detach(activity)
        DataUsage.setThreshold(activity, 250_000_000L)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNull("home not in front", alert())
        assertEquals(1, DataUsage.pendingAlert)
        DataAlert.attach(activity)
        try { assertNotNull(alert()) } finally { DataAlert.detach(activity) }
    }
}
