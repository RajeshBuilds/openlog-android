package cloud.openlog.demo

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import cloud.openlog.replay.OpenLog

/**
 * Home screen. Shows masked-by-default content (a balance, an avatar image, and a
 * deliberately unmasked note), lets you toggle recording, and navigates to the
 * login form and the recorded-JSON viewer.
 */
class MainActivity : Activity() {

    private lateinit var recordingStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recordingStatus = findViewById(R.id.recordingStatus)

        findViewById<Button>(R.id.toggleRecordingButton).setOnClickListener {
            if (OpenLog.isRecording()) {
                OpenLog.stop()
            } else {
                OpenLog.setConsent(true)
                OpenLog.start()
            }
            updateStatus()
        }

        findViewById<Button>(R.id.goToLoginButton).setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }

        findViewById<Button>(R.id.goToViewerButton).setOnClickListener {
            startActivity(Intent(this, RecordingViewerActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        recordingStatus.setText(
            if (OpenLog.isRecording()) R.string.recording_status_on else R.string.recording_status_off,
        )
    }
}
