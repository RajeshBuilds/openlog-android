package cloud.openlog.demo

import android.app.Application
import cloud.openlog.replay.OpenLog

/**
 * Initializes the OpenLog capture SDK and starts a recording session.
 *
 * In a real banking app, [OpenLog.setConsent] would reflect an explicit user
 * consent decision (Part 4); here we grant it up front so the demo records from
 * launch. Masking stays on by default — text and images are never captured raw.
 */
class DemoApp : Application() {

    override fun onCreate() {
        super.onCreate()

        OpenLog.init(
            context = this,
            config = OpenLog.Config(
                maskAllText = true,
                maskAllImages = true,
                sampleRate = 1.0,
                throttleMs = 1_000L,
            ),
        )

        OpenLog.setConsent(true)
        OpenLog.start()
    }
}
