package cloud.openlog.replay.sink

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The `X-OpenLog-Device` payload (INGEST_API_SPEC §3): a single-line JSON object
 * describing the device, persisted with the session and replayed by the web UI.
 * Sizes are density-normalized (dp), consistent with wireframe geometry (golden
 * rule #3).
 */
@Serializable
data class DeviceInfo(
    val os: String = "Android",
    val osVersion: String,
    val model: String,
    val density: Float,
    val w: Int,
    val h: Int,
    val appVersion: String,
) {
    /** The compact single-line JSON for the `X-OpenLog-Device` header. */
    fun toHeaderJson(): String = SinkJson.encodeToString(serializer(), this)
}

/**
 * Per-session upload metadata, persisted as `session.json` in the session's queue
 * dir so [ReplayUploadWorker] has everything it needs to build ingest requests
 * without the in-memory [HttpSessionSink] (the worker can run after the process
 * that recorded the session has died).
 */
@Serializable
data class SessionMeta(
    val sessionId: String,
    val baseUrl: String,
    val token: String,
    val appId: String,
    val sdkVersion: String,
    val device: DeviceInfo,
    val extraHeaders: Map<String, String> = emptyMap(),
)

/** Shared JSON for sink-internal metadata (not the wire contract). */
internal val SinkJson: Json = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
}
