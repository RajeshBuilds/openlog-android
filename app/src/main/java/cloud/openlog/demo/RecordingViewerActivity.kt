package cloud.openlog.demo

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import cloud.openlog.replay.OpenLog
import java.io.File
import java.util.concurrent.Executors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Reads the current session's NDJSON file and renders it as pretty-printed JSON.
 * Each line of the file is one wire event ({type,timestamp,data}); we flush the
 * capture pipeline first so the on-disk file is up to date before reading.
 */
class RecordingViewerActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private val pretty = Json { prettyPrint = true }

    private lateinit var filePathView: TextView
    private lateinit var jsonOutput: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recording_viewer)

        filePathView = findViewById(R.id.filePath)
        jsonOutput = findViewById(R.id.jsonOutput)

        findViewById<Button>(R.id.refreshButton).setOnClickListener { load() }
        findViewById<Button>(R.id.copyPathButton).setOnClickListener { copyPath() }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun load() {
        val file = OpenLog.currentSessionFile()
        filePathView.text = file?.absolutePath ?: ""
        jsonOutput.setText(R.string.loading)

        io.execute {
            // Flush queued capture work to disk before reading (off the main thread).
            OpenLog.flush()
            val rendered = renderFile(file)
            runOnUiThread { jsonOutput.text = rendered }
        }
    }

    private fun renderFile(file: File?): CharSequence {
        if (file == null || !file.exists()) return getString(R.string.no_recording)
        return try {
            val lines = file.readLines().filter { it.isNotBlank() }
            if (lines.isEmpty()) {
                getString(R.string.no_recording)
            } else {
                lines.joinToString("\n\n") { prettyLine(it) }
            }
        } catch (t: Throwable) {
            "Could not read recording: ${t.message}"
        }
    }

    private fun prettyLine(line: String): String = try {
        val element: JsonElement = pretty.parseToJsonElement(line)
        pretty.encodeToString(JsonElement.serializer(), element)
    } catch (_: Throwable) {
        line // fall back to the raw line if it doesn't parse
    }

    private fun copyPath() {
        val path = filePathView.text?.toString().orEmpty()
        if (path.isBlank()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("openlog-session-path", path))
        Toast.makeText(this, path, Toast.LENGTH_SHORT).show()
    }
}
