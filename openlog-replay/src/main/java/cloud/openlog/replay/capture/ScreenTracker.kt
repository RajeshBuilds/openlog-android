package cloud.openlog.replay.capture

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager

/**
 * Tracks the current foreground screen name.
 *
 * Baseline is the resumed **Activity** class name (via `ActivityLifecycleCallbacks`).
 * When androidx.fragment is present on the host classpath, it is refined to the
 * resumed **Fragment** class name, so single-Activity / multi-Fragment apps
 * (Jetpack Navigation, bottom-nav, etc.) report a per-fragment screen rather than
 * one Activity name for everything.
 *
 * androidx.fragment is an OPTIONAL dependency (`compileOnly`): the fragment-touching
 * code lives in [FragmentScreenBinder], which is only loaded after a runtime check
 * confirms the class exists, so hosts without fragments never hit a
 * `NoClassDefFoundError`.
 */
internal class ScreenTracker {

    @Volatile
    var currentScreen: String? = null
        private set

    private var application: Application? = null

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            FragmentScreens.attachIfPossible(activity, this@ScreenTracker)
        }

        override fun onActivityResumed(activity: Activity) {
            currentScreen = activity.javaClass.simpleName
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
        currentScreen = null
    }

    /** Called (on the main thread) by the fragment binder when a fragment becomes current. */
    fun onFragmentScreen(name: String) {
        currentScreen = name
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
                        tracker.onFragmentScreen(f.javaClass.simpleName)
                    }
                }
            },
            /* recursive = */ true,
        )
    }
}
