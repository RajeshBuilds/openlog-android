package cloud.openlog.demo

import android.os.Bundle
import android.widget.Button
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity

/**
 * A single Activity that hosts multiple fragments — the common Jetpack/Navigation
 * pattern. The SDK should report the *fragment* class name as the screen
 * (AccountsFragment / TransactionsFragment), not just this Activity's name.
 */
class FragmentHostActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fragment_host)

        findViewById<Button>(R.id.btnAccounts).setOnClickListener { show(AccountsFragment()) }
        findViewById<Button>(R.id.btnTransactions).setOnClickListener { show(TransactionsFragment()) }

        if (savedInstanceState == null) show(AccountsFragment())
    }

    private fun show(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }
}
