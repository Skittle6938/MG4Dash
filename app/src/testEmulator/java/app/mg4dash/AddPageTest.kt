package app.mg4dash

import app.mg4dash.ui.WebCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The web app menu's "Add this page" (#98): the page shown, only when it is not in the list yet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AddPageTest {
    private val urls = listOf("https://abetterrouteplanner.com", "https://m.youtube.com")

    @Test fun pageFollowedIsOffered() =
        assertEquals("https://m.youtube.com/watch?v=abc", WebCard.pageToAdd(urls, "https://m.youtube.com/watch?v=abc"))

    @Test fun pageAlreadyInTheListIsNot() = assertNull(WebCard.pageToAdd(urls, "https://m.youtube.com"))

    @Test fun noPageYetNothingOffered() {
        assertNull(WebCard.pageToAdd(urls, null))
        assertNull(WebCard.pageToAdd(urls, "about:blank"))
    }

    @Test fun addedOnceNoDuplicate() {
        val page = "https://homeassistant.local:8123/lovelace/car"
        val after = urls + WebCard.pageToAdd(urls, page)!!
        assertNull("offered again once added", WebCard.pageToAdd(after, page))
    }
}
