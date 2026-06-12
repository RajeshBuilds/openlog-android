package cloud.openlog.replay.capture

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import cloud.openlog.replay.wire.ScreenAction
import cloud.openlog.replay.wire.ScreenKind

/**
 * Tracks the foreground screen as a two-level hierarchy: the resumed **Activity**
 * (via `ActivityLifecycleCallbacks`) and, when androidx.fragment is on the host
 * classpath, the resumed **Fragment** inside it. Transitions are reported as an
 * ordered batch of [Change]s so the stream reads as nested scopes:
 *
 * ```
 * enter LoginActivity            (activity)
 *   enter CredentialsFragment    (fragment, parent = LoginActivity)
 *   exit  CredentialsFragment
 *   enter OtpFragment            (fragment, parent = LoginActivity)
 *   exit  OtpFragment
 * exit  LoginActivity
 * enter DashboardActivity        (activity)
 * ...
 * ```
 *
 * Changes are reported through [onTransition] **at lifecycle-callback time** (the
 * moment the screen actually became foreground), so the events carry the real
 * transition timestamp — never the time a periodic capture tick noticed.
 *
 * androidx.fragment is an OPTIONAL dependency (`compileOnly`): the fragment-touching
 * code lives in [FragmentScreenBinder], which is only loaded after a runtime check
 * confirms the class exists, so hosts without fragments never hit a
 * `NoClassDefFoundError`.
 */
internal class ScreenTracker(
    private val onTransition: (changes: List<Change>, timestampMs: Long) -> Unit,
) {

    /** One enter/exit step of a transition. [parent] is the host Activity for fragments. */
    data class Change(
        val action: String,
        val name: String,
        val kind: String,
        val parent: String? = null,
    )

    @Volatile
    var currentActivity: String? = null
        private set

    @Volatile
    var currentFragment: String? = null
        private set

    /** The most specific current screen: the fragment when one is resumed, else the activity. */
    val currentScreen: String? get() = currentFragment ?: currentActivity

    private var application: Application? = null

    /** Main thread. A new Activity is foreground: close the old scopes, open the new. */
    private fun onActivityScreen(name: String) {
        if (name == currentActivity) return
        val changes = ArrayList<Change>(3)
        currentFragment?.let { changes += Change(ScreenAction.EXIT, it, ScreenKind.FRAGMENT, currentActivity) }
        currentActivity?.let { changes += Change(ScreenAction.EXIT, it, ScreenKind.ACTIVITY) }
        changes += Change(ScreenAction.ENTER, name, ScreenKind.ACTIVITY)
        currentFragment = null
        currentActivity = name
        report(changes)
    }

    /** Main thread (via the fragment binder). A fragment became current inside [host]. */
    fun onFragmentScreen(name: String, host: String?) {
        if (name == currentFragment) return
        val parent = host ?: currentActivity
        val changes = ArrayList<Change>(2)
        currentFragment?.let { changes += Change(ScreenAction.EXIT, it, ScreenKind.FRAGMENT, currentActivity) }
        changes += Change(ScreenAction.ENTER, name, ScreenKind.FRAGMENT, parent)
        currentFragment = name
        report(changes)
    }

    private fun report(changes: List<Change>) {
        runCatching { onTransition(changes, System.currentTimeMillis()) }
    }

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            FragmentScreens.attachIfPossible(activity, this@ScreenTracker)
        }

        override fun onActivityResumed(activity: Activity) {
            onActivityScreen(activity.javaClass.simpleName)
        }

        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    fun install(context: Context) {
        val app = context.applicationContext as? Application ?: return
        application = app
        runCatching { app.registerActivityLifecycleCallbacks(callbacks) }
    }

    fun uninstall() {
        application?.let { app -> runCatching { app.unregisterActivityLifecycleCallbacks(callbacks) } }
        application = null
        currentActivity = null
        currentFragment = null
    }
}

/** Gate that only touches fragment APIs when androidx.fragment is on the classpath. */
internal object FragmentScreens {
    private val available: Boolean = try {
        Class.forName("androidx.fragment.app.FragmentActivity")
        true
    } catch (_: Throwable) {
        false
    }

    fun attachIfPossible(activity: Activity, tracker: ScreenTracker) {
        if (!available) return
        runCatching { FragmentScreenBinder.attach(activity, tracker) }
    }
}

/** Loaded only when androidx.fragment is present (see [FragmentScreens]). */
internal object FragmentScreenBinder {
    fun attach(activity: Activity, tracker: ScreenTracker) {
        val fragmentActivity = activity as? FragmentActivity ?: return
        fragmentActivity.supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
                    // Ignore headless / view-less fragments and anonymous holders.
                    if (f.view != null && !f.javaClass.isAnonymousClass) {
                        tracker.onFragmentScreen(
                            f.javaClass.simpleName,
                            f.activity?.javaClass?.simpleName,
                        )
                    }
                }
            },
            /* recursive = */ true,
        )
    }
}
