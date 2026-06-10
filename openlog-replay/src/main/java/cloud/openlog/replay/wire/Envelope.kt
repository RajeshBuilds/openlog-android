package cloud.openlog.replay.wire

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement

// ---------------------------------------------------------------------------
// Typed `data` payloads for each event shape (Part 2.2). These are encoded to a
// JsonElement and wrapped in an Event by the builders below.
// ---------------------------------------------------------------------------

@Serializable
data class MetaData(
    val href: String? = null,
    val width: Int,
    val height: Int,
)

@Serializable
data class InitialOffset(
    val top: Int = 0,
    val left: Int = 0,
)

@Serializable
data class FullSnapshotData(
    val wireframes: List<Wireframe>,
    val initialOffset: InitialOffset = InitialOffset(),
)

/** A single add/update entry: the full wireframe of the (new or changed) node. */
@Serializable
data class NodeMutation(
    val parentId: Int,
    val wireframe: Wireframe,
)

/** A single remove entry. */
@Serializable
data class NodeRemoved(
    val parentId: Int,
    val id: Int,
)

/**
 * Mutation incremental payload (`source: 0`). Empty arrays are represented as
 * `null` so they are omitted from the wire (Part 2.2 / acceptance T1).
 */
@Serializable
data class MutationData(
    val source: Int = Source.MUTATION,
    val adds: List<NodeMutation>? = null,
    val removes: List<NodeRemoved>? = null,
    val updates: List<NodeMutation>? = null,
)

/** Touch incremental payload (`source: 2`). */
@Serializable
data class TouchData(
    val source: Int = Source.MOUSE,
    val type: Int,
    val id: Int,
    val x: Int,
    val y: Int,
    val pointerType: Int = Touch.POINTER,
)

/** Generic Custom payload wrapper (`tag` + arbitrary `payload`). */
@Serializable
data class CustomData(
    val tag: String,
    val payload: JsonElement,
)

@Serializable
data class KeyboardOpen(
    val open: Boolean = true,
    val height: Int,
)

@Serializable
data class KeyboardClosed(
    val open: Boolean = false,
)

/** Screen lifecycle payload (`action` = "enter"/"exit", `name` = screen/Activity name). */
@Serializable
data class ScreenPayload(
    val action: String,
    val name: String,
)

/** App lifecycle payload (`state` = "foreground"/"background"). */
@Serializable
data class AppLifecyclePayload(
    val state: String,
)

/**
 * Tap-target payload — what the user actually tapped, so a reader can follow the
 * action without hit-testing coordinates. [label] is mask-aware (asterisks when
 * the view's text would be masked).
 */
@Serializable
data class TapTargetPayload(
    val type: String? = null,
    val idName: String? = null,
    val label: String? = null,
    val x: Int,
    val y: Int,
)

object ScreenAction {
    const val ENTER = "enter"
    const val EXIT = "exit"
}

object AppState {
    const val FOREGROUND = "foreground"
    const val BACKGROUND = "background"
}

// ---------------------------------------------------------------------------
// Builders (T1). Each returns an Event whose `data` is the encoded payload.
// Every builder's output validates against rr-mobile-schema.json.
// ---------------------------------------------------------------------------

object Events {

    private inline fun <reified T> dataOf(value: T): JsonElement =
        OpenLogJson.encodeToJsonElement(value)

    /** Meta (4) — emitted once before the first FullSnapshot of a screen. */
    fun meta(timestamp: Long, href: String?, width: Int, height: Int): Event =
        Event(EventType.META, timestamp, dataOf(MetaData(href, width, height)))

    /** FullSnapshot (2). */
    fun fullSnapshot(
        timestamp: Long,
        wireframes: List<Wireframe>,
        top: Int = 0,
        left: Int = 0,
    ): Event = Event(
        EventType.FULL,
        timestamp,
        dataOf(FullSnapshotData(wireframes, InitialOffset(top, left))),
    )

    /**
     * IncrementalSnapshot mutation (3, `source: 0`). Empty lists are dropped so
     * the emitted `data` omits empty add/remove/update arrays.
     */
    fun mutation(
        timestamp: Long,
        adds: List<NodeMutation> = emptyList(),
        removes: List<NodeRemoved> = emptyList(),
        updates: List<NodeMutation> = emptyList(),
    ): Event = Event(
        EventType.INCREMENTAL,
        timestamp,
        dataOf(
            MutationData(
                adds = adds.ifEmpty { null },
                removes = removes.ifEmpty { null },
                updates = updates.ifEmpty { null },
            ),
        ),
    )

    /** IncrementalSnapshot touch (3, `source: 2`). [type] is [Touch.START]/[Touch.END]. */
    fun touch(timestamp: Long, type: Int, id: Int, x: Int, y: Int): Event =
        Event(EventType.INCREMENTAL, timestamp, dataOf(TouchData(type = type, id = id, x = x, y = y)))

    /** Custom (5) keyboard-open event with IME height (density-normalized). */
    fun keyboardOpen(timestamp: Long, height: Int): Event =
        Event(EventType.CUSTOM, timestamp, dataOf(CustomData(CustomTag.KEYBOARD, dataOf(KeyboardOpen(height = height)))))

    /** Custom (5) keyboard-closed event. */
    fun keyboardClosed(timestamp: Long): Event =
        Event(EventType.CUSTOM, timestamp, dataOf(CustomData(CustomTag.KEYBOARD, dataOf(KeyboardClosed()))))

    /** Custom (5) screen-enter event (OpenLog extension). [name] is the screen/Activity name. */
    fun screenEnter(timestamp: Long, name: String): Event =
        Event(EventType.CUSTOM, timestamp, dataOf(CustomData(CustomTag.SCREEN, dataOf(ScreenPayload(ScreenAction.ENTER, name)))))

    /** Custom (5) screen-exit event (OpenLog extension). */
    fun screenExit(timestamp: Long, name: String): Event =
        Event(EventType.CUSTOM, timestamp, dataOf(CustomData(CustomTag.SCREEN, dataOf(ScreenPayload(ScreenAction.EXIT, name)))))

    /** Custom (5) app-foreground event (OpenLog extension). */
    fun appForeground(timestamp: Long): Event =
        Event(EventType.CUSTOM, timestamp, dataOf(CustomData(CustomTag.APP_LIFECYCLE, dataOf(AppLifecyclePayload(AppState.FOREGROUND)))))

    /** Custom (5) app-background event (OpenLog extension). */
    fun appBackground(timestamp: Long): Event =
        Event(EventType.CUSTOM, timestamp, dataOf(CustomData(CustomTag.APP_LIFECYCLE, dataOf(AppLifecyclePayload(AppState.BACKGROUND)))))

    /** Custom (5) tap-target event (OpenLog extension): what the user tapped. */
    fun tapTarget(timestamp: Long, type: String?, idName: String?, label: String?, x: Int, y: Int): Event =
        Event(EventType.CUSTOM, timestamp, dataOf(CustomData(CustomTag.TAP_TARGET, dataOf(TapTargetPayload(type, idName, label, x, y)))))

    /** Generic Custom (5) event. */
    fun custom(timestamp: Long, tag: String, payload: JsonElement = JsonObject(emptyMap())): Event =
        Event(EventType.CUSTOM, timestamp, dataOf(CustomData(tag, payload)))
}
