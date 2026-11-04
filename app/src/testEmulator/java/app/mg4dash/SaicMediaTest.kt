package app.mg4dash

import android.content.pm.PackageInfo
import android.os.Parcel
import app.mg4dash.media.SaicMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class SaicMediaTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun install(pkg: String) = shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = pkg })

    @Test fun onlyAppliesWithTheServiceAndWithoutTheMediaApp() {
        assertFalse(SaicMedia.applies(app))
        install("com.saicmotor.service.media")
        assertTrue(SaicMedia.applies(app))
        install("com.saicmotor.media")
        assertFalse(SaicMedia.applies(app))
    }

    @Test fun readsTheTrackInTheServiceOrder() {
        val p = Parcel.obtain()
        p.writeInt(1); p.writeLong(7); p.writeLong(180_000)
        p.writeString("Title"); p.writeString("cover"); p.writeString("/usb/a.mp3"); p.writeString("Artist")
        p.writeString("user"); p.writeString("avatar"); p.writeString("Album")
        p.writeLong(0); p.writeLong(0); p.writeString(""); p.writeInt(0); p.writeInt(0)
        p.setDataPosition(0)
        assertEquals(SaicMedia.Track("Title", "Artist", "Album"), SaicMedia.readTrack(p))
        p.recycle()
    }

    @Test fun noTrack() {
        val p = Parcel.obtain()
        p.writeInt(0)
        p.setDataPosition(0)
        assertNull(SaicMedia.readTrack(p))
        p.recycle()
    }
}
