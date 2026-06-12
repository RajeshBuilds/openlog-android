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
import cloud.openlog.replay.wire.ScreenAction
import cloud.openlog.replay.wire.ScreenKind
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

    /**
     * The screen whose Meta+FullSnapshot was emitted last, across ALL windows.
     * The player's tree is whatever screen last sent a FullSnapshot, so when we
     * return to a kept-alive window (its own [SnapshotStatus] unchanged) after
     * another screen snapshotted, the diff against its last tree would emit
     * nothing and replay would stay stuck on the other screen. Only read/written
     * on [executor] (single thread).
     */
    private var lastEmittedScreen: String? = null

    /**
     * Resolves the Activity/Fragment screen hierarchy from lifecycle callbacks and
     * reports transitions *at the moment they happen* — so screen enter/exit events
     * carry the real transition time, not a periodic-tick time. Transitions arrive
     * as an ordered batch (e.g. exit fragment → exit activity → enter activity) so
     * the stream reads as nested scopes; we emit the markers (stamped at the
     * callback time) and force a prompt snapshot of the new screen so its
     * Meta/FullSnapshot land right after, ahead of any interaction with it.
     */
    private val screenTracker = ScreenTracker { changes, timestampMs ->
        executor.execute {
            changes.forEach { change ->
                runCatching {
                    emit(
                        when (change.action) {
                            ScreenAction.ENTER ->
                                Events.screenEnter(timestampMs, change.name, change.kind, change.parent)
                            else ->
                                Events.screenExit(timestampMs, change.name, change.kind, change.parent)
                        },
                    )
                }
            }
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
        // Capture the open screen scopes before uninstalling, then close them in
        // nesting order (fragment first, then its host activity).
        val openFragment = screenTracker.currentFragment
        val openActivity = screenTracker.currentActivity
        screenTracker.uninstall()
        runCatching { ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver) }
        Curtains.onRootViewsChangedListeners -= rootViewsListener
        synchronized(tracked) { tracked.keys.toList() }.forEach { detach(it) }
        executor.execute {
            val now = System.currentTimeMillis()
            openFragment?.let { runCatching { emit(Events.screenExit(now, it, ScreenKind.FRAGMENT, openActivity)) } }
            openActivity?.let { runCatching { emit(Events.screenExit(now, it, ScreenKind.ACTIVITY)) } }
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
            // Only the current foreground Activity's window is "the screen". Skip
            // every other tracked window — the outgoing Activity during a transition,
            // a teardown decor whose context no longer resolves to an Activity, or a
            // system/overlay window — so we never emit a second, mislabeled snapshot
            // for the same screen. (Before the first Activity resumes, currentActivity
            // is null and we capture whatever window exists.)
            val decorActivity = activityNameOf(decor)
            val currentActivity = screenTracker.currentActivity
            if (currentActivity != null && decorActivity != currentActivity) return

            val seqBefore = status.drawSequence.get()
            val root: Wireframe = graphProvider.snapshot(decor, density, policy) ?: return
            // Discard if a draw fired mid-walk — the tree we just built is already stale.
            if (status.drawSequence.get() != seqBefore) return

            val wireframes = listOf(root)
            // Name this window by its current Fragment (when it's the current Activity)
            // or the Activity itself. Screen enter/exit markers are emitted by the
            // ScreenTracker at lifecycle time; here we only (re)emit Meta + FullSnapshot
            // when the screen changes.
            val screen = screenTracker.currentScreen ?: decorActivity ?: hrefOf(decor)
            val now = System.currentTimeMillis()

            val screenChanged = screen != status.screenHref
            // Re-send in full (not diff) when the player last saw a DIFFERENT screen's
            // FullSnapshot — i.e. we came back to this window after another screen
            // snapshotted in between.
            val playerOnOtherScreen = screen != lastEmittedScreen
            if (!status.sentFullSnapshot || screenChanged || playerOnOtherScreen) {
                emit(Events.meta(now, screen, decor.width.norm(), decor.height.norm()))
                emit(Events.fullSnapshot(now, wireframes))
                status.sentFullSnapshot = true
                status.screenHref = screen
                status.lastSnapshot = wireframes
                lastEmittedScreen = screen
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

    private fun hrefOf(view: View): String =
        activityNameOf(view) ?: view.javaClass.simpleName

    /**
     * The simple name of the Activity hosting [view]'s window, or null for non-Activity
     * windows. Resolved via the PhoneWindow's context: a DecorView's own context is a
     * DecorContext wrapping the *application* context (API 24+), so unwrapping it never
     * reaches the Activity — but the Window's context is the Activity itself.
     */
    private fun activityNameOf(view: View): String? {
        var ctx: Context? = view.phoneWindow?.context ?: view.context
        while (ctx is ContextWrapper && ctx !is Activity) ctx = ctx.baseContext
        return (ctx as? Activity)?.javaClass?.simpleName
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
