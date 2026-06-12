package cloud.openlog.replay.capture

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** Screen transitions must be reported at lifecycle time (the moment they happen), with old→new. */
@RunWith(RobolectricTestRunner::class)
class ScreenTrackerTest {

    private data class Change(val old: String?, val new: String, val ts: Long)

    @Test
    fun reportsEnterAtLifecycleTimeAndTransitions() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val changes = mutableListOf<Change>()
        val tracker = ScreenTracker { old, new, ts -> changes.add(Change(old, new, ts)) }
        tracker.install(app)

        val before = System.currentTimeMillis()
        // Resuming an Activity fires onActivityResumed -> a screen change.
        Robolectric.buildActivity(Activity::class.java).setup()

        assertEquals(1, changes.size)
        assertNull("first screen has no previous", changes[0].old)
        assertEquals("Activity", changes[0].new)
        assertTrue("stamped at the transition time", changes[0].ts >= before)

        // Moving to another screen (e.g. a fragment) reports old -> new.
        tracker.onFragmentScreen("DetailScreen")
        assertEquals(2, changes.size)
        assertEquals("Activity", changes[1].old)
        assertEquals("DetailScreen", changes[1].new)

        // No spurious change when the same screen is reported again.
        tracker.onFragmentScreen("DetailScreen")
        assertEquals(2, changes.size)

        tracker.uninstall()
    }
}
