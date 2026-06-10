package cloud.openlog.replay.capture

import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.EditText
import cloud.openlog.replay.mask.MaskPolicy
import cloud.openlog.replay.mask.maskedOf
import cloud.openlog.replay.wire.Event
import cloud.openlog.replay.wire.Events
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Real-time scroll (rrweb `source: 3`) and input (rrweb `source: 5`) capture.
 *
 * Both are optional and toggled via `OpenLog.Config`. Performance discipline
 * (golden rule #6): every callback here runs on the main thread but does only O(1)
 * work — read an offset / mask a short string and hand the event to [emitOffThread],
 * which serializes on the background executor. The cost-bearing operations are:
 *  - a throttled (~1s) main-thread *scan* of the tree to attach input watchers and
 *    refresh the scrollable set (driven by the draw loop);
 *  - a per-decor [ViewTreeObserver.OnScrollChangedListener] (additive, so it never
 *    clobbers the app's own listeners) throttled to [scrollThrottleMs].
 *
 * Listeners are attached additively and tracked in [WeakHashMap]s so views are
 * instrumented once and never pinned in memory.
 */
internal class InteractionInstrumenter(
    private val density: Float,
    private val policy: MaskPolicy,
    private val captureScrolls: Boolean,
    private val captureInputs: Boolean,
    private val scrollThrottleMs: Long,
    private val emitOffThread: (Event) -> Unit,
) {
    private companion object {
        const val SCAN_INTERVAL_MS = 1_000L

        // View.computeVertical/HorizontalScrollOffset() are protected; reflect once so
        // we can read absolute offsets for any scrollable (RecyclerView, ScrollView,
        // ListView, …) without depending on those libraries.
        val vOffsetMethod = runCatching {
            View::class.java.getDeclaredMethod("computeVerticalScrollOffset").apply { isAccessible = true }
        }.getOrNull()
        val hOffsetMethod = runCatching {
            View::class.java.getDeclaredMethod("computeHorizontalScrollOffset").apply { isAccessible = true }
        }.getOrNull()
    }

    private class Offset(var x: Int, var y: Int)

    private class DecorState {
        var hasScanned = false
        var lastScanMs = 0L
        var lastScrollEmitMs = 0L
        val scrollables = WeakHashMap<View, Offset>()
        var scrollListener: ViewTreeObserver.OnScrollChangedListener? = null
    }

    private val instrumentedInputs = WeakHashMap<View, Boolean>()
    private val decorStates = WeakHashMap<View, DecorState>()

    /** Called on the main thread from the draw loop. Throttled internally. */
    fun onDraw(decor: View) {
        if (!captureScrolls && !captureInputs) return
        val state = decorStates.getOrPut(decor) {
            DecorState().also { installScrollListener(decor, it) }
        }
        val now = SystemClock.uptimeMillis()
        if (state.hasScanned && now - state.lastScanMs < SCAN_INTERVAL_MS) return
        state.hasScanned = true
        state.lastScanMs = now
        runCatching { scan(decor, state) }
    }

    /** Called on the main thread when a window detaches. */
    fun release(decor: View) {
        val state = decorStates.remove(decor) ?: return
        state.scrollListener?.let { listener ->
            runCatching { decor.viewTreeObserver.removeOnScrollChangedListener(listener) }
        }
    }

    private fun installScrollListener(decor: View, state: DecorState) {
        if (!captureScrolls) return
        val listener = ViewTreeObserver.OnScrollChangedListener { onScroll(state) }
        runCatching { decor.viewTreeObserver.addOnScrollChangedListener(listener) }
        state.scrollListener = listener
    }

    /** Throttled tree scan: attach input watchers, refresh the scrollable set. */
    private fun scan(view: View, state: DecorState) {
        if (captureInputs && view is EditText && instrumentedInputs.put(view, true) == null) {
            attachTextWatcher(view)
        }
        if (captureScrolls && view.isScrollable() && !state.scrollables.containsKey(view)) {
            state.scrollables[view] = Offset(hOffset(view), vOffset(view))
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                view.getChildAt(i)?.let { scan(it, state) }
            }
        }
    }

    private fun attachTextWatcher(edit: EditText) {
        val id = System.identityHashCode(edit)
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val masked = if (policy.maskText(edit, ancestorUnmasked = false)) {
                    maskedOf(s)
                } else {
                    s?.toString().orEmpty()
                }
                emitOffThread(Events.input(System.currentTimeMillis(), id, text = masked))
            }
        })
    }

    private fun onScroll(state: DecorState) {
        if (!captureScrolls) return
        val now = SystemClock.uptimeMillis()
        if (now - state.lastScrollEmitMs < scrollThrottleMs) return
        state.lastScrollEmitMs = now
        for (view in state.scrollables.keys.toList()) {
            val offset = state.scrollables[view] ?: continue
            if (!view.isAttachedToWindow) continue
            val x = hOffset(view)
            val y = vOffset(view)
            if (x == offset.x && y == offset.y) continue
            offset.x = x
            offset.y = y
            emitOffThread(
                Events.scroll(
                    System.currentTimeMillis(),
                    System.identityHashCode(view),
                    (x / density).roundToInt(),
                    (y / density).roundToInt(),
                ),
            )
        }
    }

    private fun View.isScrollable(): Boolean =
        canScrollVertically(1) || canScrollVertically(-1) ||
            canScrollHorizontally(1) || canScrollHorizontally(-1)

    private fun vOffset(v: View): Int =
        runCatching { vOffsetMethod?.invoke(v) as? Int }.getOrNull() ?: v.scrollY

    private fun hOffset(v: View): Int =
        runCatching { hOffsetMethod?.invoke(v) as? Int }.getOrNull() ?: v.scrollX
}
