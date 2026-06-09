package cloud.openlog.replay.capture

import android.os.SystemClock
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Leading + trailing throttle (SPEC.md T3). At most one action runs per
 * [intervalMs]; the first call in a window runs immediately (leading edge) and a
 * final call is guaranteed after the window (trailing edge) so the last state of a
 * rapidly-redrawing screen is still captured.
 *
 * All actions are dispatched on [scheduler] (a single-thread background executor)
 * so no capture work ever touches the main thread.
 */
class Throttler(
    private val intervalMs: Long,
    private val scheduler: ScheduledExecutorService,
) {
    private val lock = Any()
    private var lastRunAt = 0L
    private var trailingScheduled = false

    fun submit(action: () -> Unit) {
        synchronized(lock) {
            val now = SystemClock.uptimeMillis()
            val elapsed = now - lastRunAt
            when {
                lastRunAt == 0L || elapsed >= intervalMs -> {
                    lastRunAt = now
                    scheduler.execute(runCatching(action))
                }
                !trailingScheduled -> {
                    trailingScheduled = true
                    val delay = intervalMs - elapsed
                    scheduler.schedule({
                        synchronized(lock) {
                            trailingScheduled = false
                            lastRunAt = SystemClock.uptimeMillis()
                        }
                        runCatching(action).run()
                    }, delay, TimeUnit.MILLISECONDS)
                }
                // else: a trailing run is already pending; coalesce.
            }
        }
    }

    private fun runCatching(action: () -> Unit): Runnable = Runnable {
        try {
            action()
        } catch (_: Throwable) {
            // Capture must never crash the host app.
        }
    }
}
