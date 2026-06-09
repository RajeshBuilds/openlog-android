package cloud.openlog.replay.verify

import cloud.openlog.replay.diff.SnapshotDiff
import cloud.openlog.replay.wire.Event
import cloud.openlog.replay.wire.MobileNodeType
import cloud.openlog.replay.wire.OpenLogJson
import cloud.openlog.replay.wire.Wireframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T6 acceptance: moving one element emits a single update; add/remove emit matching entries. */
class SnapshotDiffTest {

    private fun div(id: Int, children: List<Wireframe> = emptyList(), x: Int = 0, y: Int = 0) =
        Wireframe(id = id, x = x, y = y, width = 100, height = 100, type = MobileNodeType.DIV, childWireframes = children.ifEmpty { null })

    private fun text(id: Int, t: String, x: Int = 0, y: Int = 0) =
        Wireframe(id = id, x = x, y = y, width = 50, height = 20, type = MobileNodeType.TEXT, text = t)

    @Test
    fun movingOneElementEmitsSingleUpdate() {
        val before = listOf(div(1, listOf(text(2, "a", y = 0), text(3, "b", y = 30))))
        val after = listOf(div(1, listOf(text(2, "a", y = 5), text(3, "b", y = 30)))) // node 2 moved

        val result = SnapshotDiff.diff(before, after)
        assertTrue(result.adds.isEmpty())
        assertTrue(result.removes.isEmpty())
        assertEquals(1, result.updates.size)
        assertEquals(2, result.updates[0].wireframe.id)
        assertEquals(1, result.updates[0].parentId)
        assertNull("update wireframe must be flattened", result.updates[0].wireframe.childWireframes)
    }

    @Test
    fun addingAndRemovingViewsEmitsMatchingEntries() {
        val before = listOf(div(1, listOf(text(2, "a"))))
        val after = listOf(div(1, listOf(text(3, "c")))) // remove 2, add 3

        val result = SnapshotDiff.diff(before, after)
        assertEquals(1, result.adds.size)
        assertEquals(3, result.adds[0].wireframe.id)
        assertEquals(1, result.adds[0].parentId)
        assertEquals(1, result.removes.size)
        assertEquals(2, result.removes[0].id)
        assertEquals(1, result.removes[0].parentId)
        assertTrue(result.updates.isEmpty())
    }

    @Test
    fun identicalTreesProduceNoEvent() {
        val tree = listOf(div(1, listOf(text(2, "a"))))
        assertNull(SnapshotDiff.mutationEvent(tree, tree, 123L))
    }

    @Test
    fun mutationEventValidatesAgainstSchema() {
        val before = listOf(div(1, listOf(text(2, "a", y = 0))))
        val after = listOf(div(1, listOf(text(2, "a", y = 5), text(3, "new"))))
        val event: Event = SnapshotDiff.mutationEvent(before, after, 456L)!!
        SchemaValidator.assertValid(OpenLogJson.encodeToString(Event.serializer(), event))
    }
}
