package cloud.openlog.replay.capture

import cloud.openlog.replay.wire.Wireframe
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-decorView capture state (SPEC.md T2/T6), held in a WeakHashMap keyed by the
 * decor view so it is collected when the window goes away.
 */
class SnapshotStatus {
    /** True once Meta + FullSnapshot have been emitted for the current screen. */
    @Volatile
    var sentFullSnapshot: Boolean = false

    /** Last emitted wireframe tree, used as the "previous" side of the diff. */
    @Volatile
    var lastSnapshot: List<Wireframe>? = null

    /** Screen identity (Meta `href`). A change starts a new Meta→FullSnapshot pair. */
    @Volatile
    var screenHref: String? = null

    /** Current soft-keyboard visibility, to debounce keyboard custom events. */
    @Volatile
    var keyboardVisible: Boolean = false

    /**
     * Monotonic draw counter. Incremented on every `onDraw` (main thread). The
     * capture thread samples it before/after a walk and discards the snapshot if it
     * changed mid-walk (Part 4: "discard the snapshot if a draw fires mid-walk").
     */
    val drawSequence: AtomicInteger = AtomicInteger(0)
}
