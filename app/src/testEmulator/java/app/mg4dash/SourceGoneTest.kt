package app.mg4dash

import app.mg4dash.media.MediaHub
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Android Auto that drops for a moment is waited for before another source (27/11, #137). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SourceGoneTest {
    @Test fun androidAutoIsGivenTimeToComeBack() {
        assertEquals(15_000L, MediaHub.goneAfter("com.allgo.remoteui.mediabrowserservice", true))
        assertEquals(1_000L, MediaHub.goneAfter("com.android.bluetooth", false))
    }
}
