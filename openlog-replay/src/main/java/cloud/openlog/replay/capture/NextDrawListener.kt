package cloud.openlog.replay.capture

import android.view.View
import android.view.ViewTreeObserver

/**
 * Bridges a decor view's draw loop to capture (SPEC.md T3). On every frame
 * `onDraw` fires on the main thread; we only bump the draw counter and hand off to
 * [onDraw] (which is expected to throttle and submit work to a background thread).
 * No capture work happens here — the main thread stays free (golden rule #6).
 *
 * Mirrors the mechanism of PostHog's `NextDrawListener`, reimplemented clean-room.
 */
internal class DrawTrigger(
    private val onDraw: () -> Unit,
) : ViewTreeObserver.OnDrawListener {

    @Volatile
    private var registered = false

    override fun onDraw() {
        // Must be cheap and non-throwing — runs in the draw pipeline.
        try {
            onDraw.invoke()
        } catch (_: Throwable) {
        }
    }

    fun register(view: View) {
        val observer = view.viewTreeObserver
        if (!registered && observer.isAlive) {
            observer.addOnDrawListener(this)
            registered = true
        }
    }

    fun unregister(view: View) {
        if (!registered) return
        val observer = view.viewTreeObserver
        if (observer.isAlive) {
            try {
                observer.removeOnDrawListener(this)
            } catch (_: Throwable) {
            }
        }
        registered = false
    }
}
