package app.mg4dash

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.mg4dash.system.ProcessStats
import app.mg4dash.ui.Kit
import app.mg4dash.ui.ProcessDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The running apps window (24/11, measured on the car): its lines are made once and only their numbers
 * change, a new order places the same lines again, MG4Dash's own line says it includes the list, 3 s refresh.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "fr")
class ProcessListTest {
    private fun proc(pid: Int, pkg: String, label: String, cpu: Float, service: Boolean = false) =
        ProcessStats.Proc(pid, pkg, label, service, cpu, false, 100)

    private fun texts(v: View): List<String> = when (v) {
        is TextView -> listOf(v.text.toString())
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun rows(l: ProcessDialog.Lines) = (0 until l.list.childCount).map { l.list.getChildAt(it) }.filter { it is ViewGroup }

    @Test fun linesAreMadeOnceAndOnlyTheirNumbersChange() {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        val l = ProcessDialog.Lines(a, Kit(a), Kit(a).column())
        l.update(listOf(proc(1, "com.a", "Alpha", 5f), proc(2, "com.b", "Beta", 2f)), ProcessDialog.BY_CPU, true)
        assertEquals(2, l.made)
        val first = rows(l)
        assertTrue(texts(first[0]).toString(), "5,0 %" in texts(first[0]))
        // Same processes, new numbers: no line made, the same views, the new number shown
        l.update(listOf(proc(1, "com.a", "Alpha", 7f), proc(2, "com.b", "Beta", 1f)), ProcessDialog.BY_CPU, true)
        assertEquals(2, l.made)
        assertSame(first[0], rows(l)[0])
        assertTrue("7,0 %" in texts(rows(l)[0]))
        // The order changes: the same lines, swapped
        l.update(listOf(proc(1, "com.a", "Alpha", 1f), proc(2, "com.b", "Beta", 9f)), ProcessDialog.BY_CPU, true)
        assertEquals(2, l.made)
        assertSame(first[1], rows(l)[0]); assertSame(first[0], rows(l)[1])
        // A process gone: its line leaves
        l.update(listOf(proc(2, "com.b", "Beta", 9f)), ProcessDialog.BY_CPU, true)
        assertEquals(1, rows(l).size)
    }

    @Test fun ownLineSaysItIncludesTheList() {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        val l = ProcessDialog.Lines(a, Kit(a), Kit(a).column())
        l.update(listOf(proc(1, a.packageName, "MG4Dash", 3f), proc(2, "com.b", "Beta", 2f)), ProcessDialog.BY_CPU, true)
        val note = a.getString(R.string.ps_self)
        assertTrue(note in texts(rows(l)[0]))
        assertFalse(note in texts(rows(l)[1]))
    }

    @Test fun refreshesEveryThreeSeconds() = assertEquals(3_000L, ProcessDialog.REFRESH_MS)
}
