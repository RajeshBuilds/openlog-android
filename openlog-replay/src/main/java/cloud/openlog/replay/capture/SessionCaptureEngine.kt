package cloud.openlog.replay.capture

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.MotionEvent
import android.view.View
import android.view.Window
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import cloud.openlog.replay.correlate.Correlation
import cloud.openlog.replay.diff.SnapshotDiff
import cloud.openlog.replay.graph.ScreenGraphProvider
import cloud.openlog.replay.mask.MaskPolicy
import cloud.openlog.replay.sink.SessionSink
import cloud.openlog.replay.wire.Event
import cloud.openlog.replay.wire.Events
import cloud.openlog.replay.wire.Touch
import cloud.openlog.replay.wire.Wireframe
import curtains.Curtains
import curtains.OnRootViewsChangedListener
import curtains.TouchEventInterceptor
import curtains.phoneWindow
import curtains.touchEventInterceptors
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import kotlin.math.roundToInt

/**
 * The capture engine (SPEC.md T2/T3/T6/T7). Ties together window discovery,
 * the throttled draw loop, the view→wireframe walk, the diff, touch and keyboard
 * capture, and the sink.
 *
 * Threading: Curtains callbacks and the draw loop run on the main thread but only
 * ever *enqueue* work; the actual walk/diff/serialization happens on a single
 * background thread ([executor]) (golden rule #6, Part 4).
 */
class SessionCaptureEngine(
    context: Context,
    private val graphProvider: ScreenGraphProvider,
    private val policy: MaskPolicy,
    private val sink: SessionSink,
    private val correlation: Correlation,
    private val density: Float,
    throttleMs: Long = 1_000L,
) {
    private val appContext = context.applicationContext

    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor(NamedThreadFactory("openlog-capture"))

    private val throttler = Throttler(throttleMs, executor)

    /** Per-decorView capture state. Weak keys so closed windows are collected. */
    private val tracked: MutableMap<View, Tracking> =
        Collections.synchronizedMap(WeakHashMap<View, Tracking>())

    @Volatile
    private var started = false

    private val rootViewsListener = OnRootViewsChangedListener { view, added ->
        if (added) attach(view) else detach(view)
    }

    private class Tracking(
        val status: SnapshotStatus,
        val drawTrigger: DrawTrigger,
        val touchInterceptor: TouchEventInterceptor,
        val viewRef: WeakReference<View>,
        // Weak so the value never pins the WeakHashMap key (Window -> decor).
        val windowRef: WeakReference<Window>,
    )

    // ---- lifecycle ---------------------------------------------------------

    fun start() {
        if (started) return
        started = true
        // Attach to every existing window, then keep up with new ones (T2).
        Curtains.rootViews.toList().forEach { attach(it) }
        Curtains.onRootViewsChangedListeners += rootViewsListener
    }

    fun stop() {
        if (!started) return
        started = false
        Curtains.onRootViewsChangedListeners -= rootViewsListener
        synchronized(tracked) { tracked.keys.toList() }.forEach { detach(it) }
        executor.execute {
            runCatching { sink.flush() }
        }
    }

    // ---- window discovery (T2) --------------------------------------------

    private fun attach(decor: View) {
        if (!started) return
        if (tracked.containsKey(decor)) return

        val status = SnapshotStatus()
        val ref = WeakReference(decor)

        val drawTrigger = DrawTrigger {
            // Main thread: bump the draw counter and enqueue a throttled capture.
            status.drawSequence.incrementAndGet()
            throttler.submit { captureFrame(ref, status) }
        }

        val touchInterceptor = TouchEventInterceptor { motionEvent, dispatch ->
            handleTouch(ref, motionEvent)
            dispatch(motionEvent) // non-consuming
        }

        val window = decor.phoneWindow

        tracked[decor] = Tracking(status, drawTrigger, touchInterceptor, ref, WeakReference(window))

        drawTrigger.register(decor)
        runCatching { window?.touchEventInterceptors?.add(0, touchInterceptor) }

        // Kick an initial capture so a static screen is recorded without waiting for a redraw.
        throttler.submit { captureFrame(ref, status) }
    }

    private fun detach(decor: View) {
        val tracking = tracked.remove(decor) ?: return
        runCatching { tracking.drawTrigger.unregister(decor) }
        runCatching { tracking.windowRef.get()?.touchEventInterceptors?.remove(tracking.touchInterceptor) }
    }

    // ---- frame capture (T3/T6) --------------------------------------------

    private fun captureFrame(ref: WeakReference<View>, status: SnapshotStatus) {
        val decor = ref.get() ?: return
        try {
            if (!decor.isAttachedToWindow) return
            // Skip animation-only redraws so animating screens don't spam (Part 4).
            if (decor.hasTransientState()) return

            val seqBefore = status.drawSequence.get()
            val root: Wireframe = graphProvider.snapshot(decor, density, policy) ?: return
            // Discard if a draw fired mid-walk — the tree we just built is already stale.
            if (status.drawSequence.get() != seqBefore) return

            val wireframes = listOf(root)
            val href = hrefOf(decor)
            val now = System.currentTimeMillis()

            val screenChanged = href != status.screenHref
            if (!status.sentFullSnapshot || screenChanged) {
                emit(Events.meta(now, href, decor.width.norm(), decor.height.norm()))
                emit(Events.fullSnapshot(now, wireframes))
                status.sentFullSnapshot = true
                status.screenHref = href
                status.lastSnapshot = wireframes
            } else {
                val previous = status.lastSnapshot.orEmpty()
                SnapshotDiff.mutationEvent(previous, wireframes, now)?.let { emit(it) }
                status.lastSnapshot = wireframes
            }

            emitKeyboardTransition(decor, status, now)
        } catch (_: Throwable) {
            // Never crash the capture thread (Part 4).
        }
    }

    // ---- keyboard (T7) -----------------------------------------------------

    private fun emitKeyboardTransition(decor: View, status: SnapshotStatus, now: Long) {
        val insets = ViewCompat.getRootWindowInsets(decor) ?: return
        val imeType = WindowInsetsCompat.Type.ime()
        val visible = insets.isVisible(imeType)
        if (visible == status.keyboardVisible) return
        status.keyboardVisible = visible
        if (visible) {
            val height = insets.getInsets(imeType).bottom.norm()
            emit(Events.keyboardOpen(now, height))
        } else {
            emit(Events.keyboardClosed(now))
        }
    }

    // ---- touch (T7) --------------------------------------------------------

    private fun handleTouch(ref: WeakReference<View>, event: MotionEvent) {
        val type = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> Touch.START
            MotionEvent.ACTION_UP -> Touch.END
            else -> return
        }
        // Copy off the live event before going off-thread, then recycle (Part 4).
        val copy = MotionEvent.obtain(event)
        executor.execute {
            try {
                val x = (copy.rawX / density).roundToInt()
                val y = (copy.rawY / density).roundToInt()
                val id = ref.get()?.let { System.identityHashCode(it) } ?: ROOT_ID_FALLBACK
                emit(Events.touch(System.currentTimeMillis(), type, id, x, y))
            } catch (_: Throwable) {
            } finally {
                copy.recycle()
            }
        }
    }

    // ---- helpers -----------------------------------------------------------

    private fun emit(event: Event) {
        runCatching { sink.write(event) }
    }

    private fun Int.norm(): Int = (this / density).roundToInt()

    private fun hrefOf(view: View): String {
        var ctx: Context? = view.context
        while (ctx is ContextWrapper && ctx !is Activity) ctx = ctx.baseContext
        return (ctx as? Activity)?.javaClass?.simpleName ?: view.javaClass.simpleName
    }

    private class NamedThreadFactory(private val name: String) : ThreadFactory {
        override fun newThread(r: Runnable): Thread =
            Thread(r, name).apply { isDaemon = true }
    }

    companion object {
        /** Touch fallback id when the touched wireframe is unknown (Part 2.2: root id). */
        const val ROOT_ID_FALLBACK = 5
    }
}
