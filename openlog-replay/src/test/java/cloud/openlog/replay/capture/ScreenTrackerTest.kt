package cloud.openlog.replay.capture

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import cloud.openlog.replay.wire.ScreenAction
import cloud.openlog.replay.wire.ScreenKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Screen transitions must be reported at lifecycle time as nested scopes:
 * activity enter → fragment enter/exit inside it → activity exit.
 */
@RunWith(RobolectricTestRunner::class)
class ScreenTrackerTest {

    private data class Step(val action: String, val name: String, val kind: String, val parent: String?)

    private val steps = mutableListOf<Step>()
    private val stamps = mutableListOf<Long>()

    private val tracker = ScreenTracker { changes, ts ->
        changes.forEach { steps.add(Step(it.action, it.name, it.kind, it.parent)) }
        stamps.add(ts)
    }

    @Test
    fun activityEnterIsReportedAtLifecycleTime() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        tracker.install(app)

        val before = System.currentTimeMillis()
        Robolectric.buildActivity(Activity::class.java).setup()

        assertEquals(listOf(Step(ScreenAction.ENTER, "Activity", ScreenKind.ACTIVITY, null)), steps)
        assertTrue("stamped at the transition time", stamps.single() >= before)
        assertEquals("Activity", tracker.currentScreen)

        tracker.uninstall()
    }

    @Test
    fun fragmentTransitionsNestInsideTheActivity() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        tracker.install(app)
        Robolectric.buildActivity(Activity::class.java).setup()
        steps.clear()

        // First fragment of the activity: just a fragment enter (activity stays open).
        tracker.onFragmentScreen("AccountsFragment", "Activity")
        assertEquals(
            listOf(Step(ScreenAction.ENTER, "AccountsFragment", ScreenKind.FRAGMENT, "Activity")),
            steps,
        )
        assertEquals("AccountsFragment", tracker.currentScreen)
        steps.clear()

        // Switching fragments: exit old → enter new, both inside the same activity.
        tracker.onFragmentScreen("TransactionsFragment", "Activity")
        assertEquals(
            listOf(
                Step(ScreenAction.EXIT, "AccountsFragment", ScreenKind.FRAGMENT, "Activity"),
                Step(ScreenAction.ENTER, "TransactionsFragment", ScreenKind.FRAGMENT, "Activity"),
            ),
            steps,
        )
        steps.clear()

        // Repeat of the same fragment: no spurious events.
        tracker.onFragmentScreen("TransactionsFragment", "Activity")
        assertTrue(steps.isEmpty())

        tracker.uninstall()
    }

    @Test
    fun activityChangeClosesFragmentThenActivityScopes() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        tracker.install(app)
        Robolectric.buildActivity(Activity::class.java).setup()
        tracker.onFragmentScreen("AccountsFragment", "Activity")
        steps.clear()

        // A different Activity resumes: exit fragment → exit activity → enter activity.
        Robolectric.buildActivity(NextActivity::class.java).setup()

        assertEquals(
            listOf(
                Step(ScreenAction.EXIT, "AccountsFragment", ScreenKind.FRAGMENT, "Activity"),
                Step(ScreenAction.EXIT, "Activity", ScreenKind.ACTIVITY, null),
                Step(ScreenAction.ENTER, "NextActivity", ScreenKind.ACTIVITY, null),
            ),
            steps,
        )
        assertEquals("NextActivity", tracker.currentScreen)

        tracker.uninstall()
    }

    class NextActivity : Activity()
}
