package cloud.openlog.replay.capture

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import cloud.openlog.replay.correlate.Correlation
import cloud.openlog.replay.diff.SnapshotDiff
import cloud.openlog.replay.graph.ScreenGraphProvider
import cloud.openlog.replay.mask.MaskPolicy
import cloud.openlog.replay.mask.maskedOf
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
import java.util.concurrent.TimeUnit
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
    private val throttleMs: Long = 1_000L,
    captureScrolls: Boolean = true,
    captureInputs: Boolean = true,
    scrollThrottleMs: Long = 100L,
) {
    private val appContext = context.applicationContext

    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor(NamedThreadFactory("openlog-capture"))

    /** Real-time scroll/input capture (rrweb source 3/5). Null when both are disabled. */
    private val instrumenter: InteractionInstrumenter? =
        if (captureScrolls || captureInputs) {
            InteractionInstrumenter(
                density = density,
                policy = policy,
                captureScrolls = captureScrolls,
                captureInputs = captureInputs,
                scrollThrottleMs = scrollThrottleMs,
                emitOffThread = { event -> executor.execute { emit(event) } },
            )
        } else {
            null
        }

    /** Per-decorView capture state. Weak keys so closed windows are collected. */
    private val tracked: MutableMap<View, Tracking> =
        Collections.synchronizedMap(WeakHashMap<View, Tracking>())

    @Volatile
    private var started = false

    /** The last screen name we emitted enter/exit for. Touched only on the capture thread. */
    private var lastEmittedScreen: String? = null

    /**
     * Resolves Activity/Fragment screen names from lifecycle callbacks and reports
     * transitions *at the moment they happen* — so screen enter/exit events carry the
     * real transition time, not a periodic-tick time. On a change we emit the markers
     * (stamped at the callback time) and force a prompt snapshot of the new screen so
     * its Meta/FullSnapshot land right after, ahead of any interaction with it.
     */
    private val screenTracker = ScreenTracker { old, new, timestampMs ->
        executor.execute {
            old?.let { runCatching { emit(Events.screenExit(timestampMs, it)) } }
            runCatching { emit(Events.screenEnter(timestampMs, new)) }
            lastEmittedScreen = new
        }
        requestPromptSnapshot()
    }

    private val rootViewsListener = OnRootViewsChangedListener { view, added ->
        if (added) attach(view) else detach(view)
    }

    /** App foreground/background via the process lifecycle (emitted off the main thread). */
    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            executor.execute { runCatching { emit(Events.appForeground(System.currentTimeMillis())) } }
        }

        override fun onStop(owner: LifecycleOwner) {
            executor.execute { runCatching { emit(Events.appBackground(System.currentTimeMillis())) } }
        }
    }

    private class Tracking(
        val status: SnapshotStatus,
        val drawTrigger: DrawTrigger,
        val touchInterceptor: TouchEventInterceptor,
        val viewRef: WeakReference<View>,
        // Weak so the value never pins the WeakHashMap key (Window -> decor).
        val windowRef: WeakReference<Window>,
        // Per-window throttle so a newly-shown screen's first snapshot is prompt
        // (leading edge) instead of waiting behind another window's recent capture.
        val throttler: Throttler,
    )

    // ---- lifecycle ---------------------------------------------------------

    fun start() {
        if (started) return
        started = true
        screenTracker.install(appContext)
        // App foreground/background events (fires onStart immediately for the current state).
        runCatching { ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver) }
        // Attach to every existing window, then keep up with new ones (T2).
        Curtains.rootViews.toList().forEach { attach(it) }
        Curtains.onRootViewsChangedListeners += rootViewsListener
    }

    fun stop() {
        if (!started) return
        started = false
        screenTracker.uninstall()
        runCatching { ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver) }
        Curtains.onRootViewsChangedListeners -= rootViewsListener
        synchronized(tracked) { tracked.keys.toList() }.forEach { detach(it) }
        executor.execute {
            lastEmittedScreen?.let { runCatching { emit(Events.screenExit(System.currentTimeMillis(), it)) } }
            lastEmittedScreen = null
            runCatching { sink.flush() }
        }
    }

    /**
     * Block until all currently-queued capture work is written and the sink is
     * flushed to durable storage. Useful for reading the session file mid-session
     * (e.g. an in-app recording viewer). Call off the main thread.
     */
    fun flushBlocking(timeoutMs: Long = 2_000L) {
        val future = executor.submit { runCatching { sink.flush() } }
        runCatching { future.get(timeoutMs, TimeUnit.MILLISECONDS) }
    }

    // ---- window discovery (T2) --------------------------------------------

    private fun attach(decor: View) {
        if (!started) return
        if (tracked.containsKey(decor)) return

        val status = SnapshotStatus()
        val ref = WeakReference(decor)
        val throttler = Throttler(throttleMs, executor)

        val drawTrigger = DrawTrigger {
            // Main thread: bump the draw counter and enqueue a throttled capture.
            status.drawSequence.incrementAndGet()
            throttler.submit { captureFrame(ref, status) }
            // Attach input watchers / refresh scrollables (throttled internally, off-loads emit).
            instrumenter?.onDraw(decor)
        }

        val touchInterceptor = TouchEventInterceptor { motionEvent, dispatch ->
            handleTouch(ref, motionEvent)
            dispatch(motionEvent) // non-consuming
        }

        val window = decor.phoneWindow

        tracked[decor] = Tracking(status, drawTrigger, touchInterceptor, ref, WeakReference(window), throttler)

        drawTrigger.register(decor)
        runCatching { window?.touchEventInterceptors?.add(0, touchInterceptor) }

        // Kick an initial capture so a static screen is recorded without waiting for a redraw.
        throttler.submit { captureFrame(ref, status) }
        instrumenter?.onDraw(decor)
    }

    /**
     * Force a prompt snapshot of the tracked windows shortly after a screen change,
     * bypassing the throttle, so the new screen's Meta/FullSnapshot land right after
     * the screen-enter marker (rather than up to one throttle interval later). Each
     * [captureFrame] self-guards: detached/unlaid windows are skipped, and an
     * unchanged tree emits nothing.
     */
    private fun requestPromptSnapshot() {
        runCatching {
            executor.schedule(
                {
                    synchronized(tracked) { tracked.values.toList() }.forEach {
                        captureFrame(it.viewRef, it.status)
                    }
                },
                PROMPT_SNAPSHOT_DELAY_MS,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    private fun detach(decor: View) {
        val tracking = tracked.remove(decor) ?: return
        runCatching { tracking.drawTrigger.unregister(decor) }
        runCatching { tracking.windowRef.get()?.touchEventInterceptors?.remove(tracking.touchInterceptor) }
        instrumenter?.release(decor)
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
            // Prefer the resumed Fragment/Activity name from the tracker; fall back to
            // the decor's hosting Activity when the tracker has nothing yet. Screen
            // enter/exit markers are emitted by the ScreenTracker at lifecycle time;
            // here we only (re)emit Meta + FullSnapshot when the screen changes.
            val screen = screenTracker.currentScreen ?: hrefOf(decor)
            val now = System.currentTimeMillis()

            val screenChanged = screen != status.screenHref
            if (!status.sentFullSnapshot || screenChanged) {
                emit(Events.meta(now, screen, decor.width.norm(), decor.height.norm()))
                emit(Events.fullSnapshot(now, wireframes))
                status.sentFullSnapshot = true
                status.screenHref = screen
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
                val decor = ref.get()
                // Hit-test the tapped view so the touch id points at the real node and
                // we can describe the target (type / idName / label).
                val target = decor?.let { runCatching { findTouchedView(it, copy.rawX, copy.rawY) }.getOrNull() }
                val node = target ?: decor
                val id = node?.let { System.identityHashCode(it) } ?: ROOT_ID_FALLBACK
                val now = System.currentTimeMillis()
                emit(Events.touch(now, type, id, x, y))
                if (type == Touch.START && target != null) {
                    emit(
                        Events.tapTarget(
                            timestamp = now,
                            type = target.javaClass.simpleName,
                            idName = target.resourceEntryName(),
                            label = maskedLabel(target),
                            x = x, y = y,
                        ),
                    )
                }
            } catch (_: Throwable) {
            } finally {
                copy.recycle()
            }
        }
    }

    /** Deepest visible view containing the raw-pixel point (topmost child wins). */
    private fun findTouchedView(root: View, x: Float, y: Float): View? {
        if (root.visibility != View.VISIBLE) return null
        val loc = IntArray(2).also { root.getLocationOnScreen(it) }
        val left = loc[0]
        val top = loc[1]
        if (x < left || y < top || x > left + root.width || y > top + root.height) return null
        if (root is ViewGroup) {
            for (i in root.childCount - 1 downTo 0) {
                val child = root.getChildAt(i) ?: continue
                findTouchedView(child, x, y)?.let { return it }
            }
        }
        return root
    }

    /** The view's resource-id entry name (e.g. "signInButton"), or null. */
    private fun View.resourceEntryName(): String? {
        val vid = id
        if (vid == View.NO_ID) return null
        return runCatching { resources.getResourceEntryName(vid) }.getOrNull()
    }

    /** The tapped view's label (contentDescription or text), masked per policy. */
    private fun maskedLabel(v: View): String? {
        val raw = v.contentDescription ?: (v as? TextView)?.text
        if (raw.isNullOrEmpty()) return null
        return if (policy.maskText(v, ancestorUnmasked = false)) maskedOf(raw) else raw.toString()
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

        /** Delay before the post-transition forced snapshot, to let the new screen lay out. */
        const val PROMPT_SNAPSHOT_DELAY_MS = 48L
    }
}
