package cloud.openlog.demo

import android.app.Application
import android.util.Log
import cloud.openlog.replay.OpenLog
import cloud.openlog.replay.sink.HttpSessionSink

/**
 * Initializes the OpenLog capture SDK and starts a recording session.
 *
 * DEBUG/demo configuration: masking is turned OFF so the recorded JSON is
 * human-readable. Each node already carries its XML resource-id name (`idName`).
 * Individual sensitive views can still be hidden with the `openlog-mask` tag (see
 * the balance on the home screen).
 *
 * A real banking build MUST keep `maskAllText`/`maskAllImages = true` (the library
 * default) so PII is never captured raw.
 *
 * Sinks: the demo always keeps a local NDJSON file (viewable in
 * [RecordingViewerActivity]); when an ingest token is configured (local.properties
 * → BuildConfig, see app/build.gradle.kts) it ALSO uploads to the backend via the
 * SDK's [HttpSessionSink] (`file = true` + `http` → both). With no token it just
 * records the local file, so the demo still runs fully offline.
 */
class DemoApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Build the HTTP sink config only when a token is present; Config validates
        // a non-blank token, and a blank one means "no upload configured".
        val http = BuildConfig.OPENLOG_INGEST_TOKEN.takeIf { it.isNotBlank() }?.let { token ->
            HttpSessionSink.Config(
                baseUrl = BuildConfig.OPENLOG_BASE_URL,
                token = token,
            )
        }

        OpenLog.init(
            context = this,
            config = OpenLog.Config(
                maskAllText = false,     // DEBUG: unmask so the JSON is readable
                maskAllImages = false,   // DEBUG
                sampleRate = 1.0,
                throttleMs = 1_000L,
                debugClassNames = true,  // DEBUG: add platform class name to each node
                http = http,             // upload via SDK when a token is configured
                file = true,             // ALSO keep a local copy so the in-app viewer works
            ),
        )

        OpenLog.setConsent(true)
        OpenLog.start()

        // Surface the session id so a tester can look the session up on the backend
        // (GET /api/sessions) — no need to pull the NDJSON off the device.
        if (http != null) {
            Log.i(TAG, "Uploading session ${OpenLog.sessionId()} to ${BuildConfig.OPENLOG_BASE_URL}")
        } else {
            Log.i(TAG, "Recording session ${OpenLog.sessionId()} to local file (no ingest token set)")
        }
    }

    private companion object {
        const val TAG = "OpenLogDemo"
    }
}
