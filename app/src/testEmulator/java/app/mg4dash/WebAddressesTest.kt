package app.mg4dash

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.mg4dash.ui.CardMenu
import app.mg4dash.ui.WebAddresses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** The web app's addresses (#98): one menu for the long press and the edit mode, and the USB file's lines. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1920dp-h720dp-land-mdpi")
class WebAddressesTest {
    @Test fun fileLinesRead() {
        val read = WebAddresses.parse(listOf(
            "﻿# my car pages", "", "https://abetterrouteplanner.com", "Home | http://homeassistant.local:8123/lovelace/car",
            "m.youtube.com", "not an address", "https://abetterrouteplanner.com",
        ))
        assertEquals(listOf("https://abetterrouteplanner.com", "http://homeassistant.local:8123/lovelace/car", "http://m.youtube.com"), read)
    }

    @Test fun exportReadBack() {
        val urls = listOf("https://abetterrouteplanner.com", "http://homeassistant.local:8123/lovelace/car")
        assertEquals(urls, WebAddresses.parse(WebAddresses.format(urls).lines()))
    }

    @Test fun importAddsOnlyTheNewOnes() =
        assertEquals(listOf("https://b.example"), WebAddresses.merge(listOf("https://a.example"), listOf("https://a.example", "https://b.example")))

    @Test fun editKeepsItsPlace() =
        assertEquals(listOf("https://a.example", "https://c.example"), WebAddresses.replace(listOf("https://a.example", "https://b.example"), "https://b.example", "https://c.example"))

    /** The rows' titles of the menu just opened. */
    private fun titles(): List<String> {
        shadowOf(Looper.getMainLooper()).idle()
        val root = ShadowDialog.getLatestDialog().window!!.decorView
        val out = mutableListOf<String>()
        fun walk(v: View) {
            // The titles (21 sp), not the marks nor the end buttons
            if (v is TextView && Math.abs(v.textSize - 21f * v.resources.displayMetrics.scaledDensity) < 0.5f) out += v.text.toString()
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        ShadowDialog.getLatestDialog().dismiss()
        return out
    }

    @Test fun sameMenuInTheCardAndTheEditMode() {
        RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", Context.MODE_PRIVATE).edit().clear().commit()
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        app.mg4dash.system.Prefs(activity).apply { webUrls = listOf("https://abetterrouteplanner.com", "https://m.youtube.com"); webUrl = "https://m.youtube.com" }
        WebAddresses.show(activity, onShow = {})
        val edit = titles()
        val back = CardMenu.Row("←", activity.getString(R.string.wc_web_back), null, false) {}
        WebAddresses.show(activity, listOf(back), page = "https://m.youtube.com/watch?v=abc", onShow = {})
        val card = titles()
        val addPage = activity.getString(R.string.wc_add_page)
        assertTrue("Add this page in the card's menu only", addPage in card && addPage !in edit)
        assertEquals("the rest the same", edit, card - listOf(activity.getString(R.string.wc_web_back), addPage))
        assertTrue(activity.getString(R.string.wc_urls_import) in edit && activity.getString(R.string.wc_urls_export) in edit)
    }
}
