package cloud.openlog.replay.diff

import cloud.openlog.replay.wire.Event
import cloud.openlog.replay.wire.Events
import cloud.openlog.replay.wire.NodeMutation
import cloud.openlog.replay.wire.NodeRemoved
import cloud.openlog.replay.wire.Wireframe

/**
 * Snapshot diffing (SPEC.md T6). Pure, Android-free, and unit-testable on a plain
 * JVM (the `tools/wire-verify` harness includes this file).
 *
 * The previous and current wireframe trees are flattened by stable `id`, then:
 *  - **adds**    = ids present now but not before
 *  - **removes** = ids present before but not now
 *  - **updates** = ids present in both whose own properties changed
 *
 * Each add/update entry carries a *flattened* wireframe (its `childWireframes`
 * nulled) plus the `parentId` of the node, exactly as the mutation wire shape
 * requires. Property comparison is done on the children-less node so that a change
 * deep in a subtree only emits an update for the node that actually changed —
 * "moving one element emits a single update, not a new Full" (T6 acceptance).
 */
object SnapshotDiff {

    /** Sentinel parent id for the top-level (screen-root) wireframes. */
    const val ROOT_PARENT_ID: Int = 0

    /** A node flattened out of its tree: its own props (no children) + its parent id. */
    data class FlatNode(val parentId: Int, val node: Wireframe)

    data class Result(
        val adds: List<NodeMutation>,
        val removes: List<NodeRemoved>,
        val updates: List<NodeMutation>,
    ) {
        val isEmpty: Boolean get() = adds.isEmpty() && removes.isEmpty() && updates.isEmpty()
    }

    /** Flatten a tree (preserving document order) into id -> [FlatNode]. */
    fun flatten(roots: List<Wireframe>): LinkedHashMap<Int, FlatNode> {
        val out = LinkedHashMap<Int, FlatNode>()
        fun walk(node: Wireframe, parentId: Int) {
            out[node.id] = FlatNode(parentId, node.withoutChildren())
            node.childWireframes?.forEach { walk(it, node.id) }
        }
        roots.forEach { walk(it, ROOT_PARENT_ID) }
        return out
    }

    /** Compute the structural diff between two trees. */
    fun diff(previous: List<Wireframe>, current: List<Wireframe>): Result {
        val prev = flatten(previous)
        val curr = flatten(current)

        val adds = ArrayList<NodeMutation>()
        val updates = ArrayList<NodeMutation>()
        for ((id, now) in curr) {
            val before = prev[id]
            when {
                before == null -> adds += NodeMutation(now.parentId, now.node)
                before != now -> updates += NodeMutation(now.parentId, now.node)
            }
        }

        val removes = ArrayList<NodeRemoved>()
        for ((id, before) in prev) {
            if (!curr.containsKey(id)) removes += NodeRemoved(before.parentId, id)
        }

        return Result(adds, removes, updates)
    }

    /**
     * Build a mutation [Event] from the diff, or `null` when nothing changed (so
     * the caller emits no event for an idle frame).
     */
    fun mutationEvent(previous: List<Wireframe>, current: List<Wireframe>, timestamp: Long): Event? {
        val result = diff(previous, current)
        if (result.isEmpty) return null
        return Events.mutation(timestamp, result.adds, result.removes, result.updates)
    }
}
