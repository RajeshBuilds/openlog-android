package cloud.openlog.demo

import android.app.Application
import cloud.openlog.replay.OpenLog

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
 */
class DemoApp : Application() {

    override fun onCreate() {
        super.onCreate()

        OpenLog.init(
            context = this,
            config = OpenLog.Config(
                maskAllText = false,   // DEBUG: unmask so the JSON is readable
                maskAllImages = false, // DEBUG
                sampleRate = 1.0,
                throttleMs = 1_000L,
            ),
        )

        OpenLog.setConsent(true)
        OpenLog.start()
    }
}
