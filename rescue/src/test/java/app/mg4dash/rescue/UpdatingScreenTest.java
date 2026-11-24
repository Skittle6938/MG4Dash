package app.mg4dash.rescue;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * "Updating MG4Dash…" (#132): MG4Dash's theme colours or its default ones, its texts, and a home only while the rescue
 * recompiles MG4Dash (disabled otherwise, so it never stays a home).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "fr")
public class UpdatingScreenTest {

    @Test public void themeColoursOrDefault() {
        int[] def = { 0xFF070C1B, 0xFF0C1531, 0xFFFFFFFF, 0xFFA0AEC0, 0xFF00D2FF };
        assertArrayEquals("nothing published", def, UpdatingActivity.colors(null));
        assertArrayEquals("unreadable", def, UpdatingActivity.colors("#12,nope"));
        assertArrayEquals(new int[] { 0xFF111111, 0xFF222222, 0xFF000000, 0xFF555555, 0xFFC62828 },
            UpdatingActivity.colors("#FF111111,#FF222222,#FF000000,#FF555555,#FFC62828"));
    }

    private static void texts(View v, List<String> out) {
        if (v instanceof TextView) out.add(((TextView) v).getText().toString());
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) texts(((ViewGroup) v).getChildAt(i), out);
    }

    @Test public void saysWhatHappens() {
        Settings.Global.putString(RuntimeEnvironment.getApplication().getContentResolver(), UpdatingActivity.COLORS_KEY,
            "#FF111111,#FF222222,#FFFFFFFF,#FF999999,#FF00D2FF");
        UpdatingActivity a = Robolectric.buildActivity(UpdatingActivity.class).setup().get();
        List<String> t = new ArrayList<>();
        texts(a.getWindow().getDecorView(), t);
        assertTrue(t.toString(), t.contains("Mise à jour de MG4Dash…"));
        assertTrue(t.toString(), t.contains("Quelques secondes, MG4Dash revient tout seul"));
    }

    @Test public void aHomeOnlyWhileShown() {
        RescueService s = Robolectric.buildService(RescueService.class).get();
        PackageManager pm = s.getPackageManager();
        ComponentName c = new ComponentName(s, UpdatingActivity.class);
        assertEquals("default: disabled in the manifest", PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, pm.getComponentEnabledSetting(c));
        assertTrue(s.showUpdating(pm, true));
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, pm.getComponentEnabledSetting(c));
        assertTrue(s.showUpdating(pm, false));
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, pm.getComponentEnabledSetting(c));
    }
}
