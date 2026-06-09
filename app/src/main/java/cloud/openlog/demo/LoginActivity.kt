package cloud.openlog.demo

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.Toast

/**
 * A second screen with form inputs (email, password, checkbox, button). Focusing a
 * field opens the soft keyboard and typing mutates inputs — exercising touch,
 * keyboard, and mutation events in the capture stream. Input values are masked.
 */
class LoginActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        findViewById<Button>(R.id.signInButton).setOnClickListener {
            Toast.makeText(this, R.string.signed_in_toast, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
