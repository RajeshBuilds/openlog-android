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
import java.io.RandomAccessFile
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
            // Read only the tail and cap the number of events so the viewer can never
            // OOM regardless of how large the recording grows.
            val allLines = readTail(file, MAX_BYTES).split('\n').filter { it.isNotBlank() }
            if (allLines.isEmpty()) return getString(R.string.no_recording)
            val truncated = allLines.size > MAX_EVENTS
            val lines = if (truncated) allLines.takeLast(MAX_EVENTS) else allLines
            val body = lines.joinToString("\n\n") { prettyLine(it) }
            if (truncated) "… showing last ${lines.size} events (file truncated) …\n\n$body" else body
        } catch (t: Throwable) {
            "Could not read recording: ${t.message}"
        }
    }

    /** Read at most [maxBytes] from the end of [file], dropping a leading partial line. */
    private fun readTail(file: File, maxBytes: Long): String {
        val length = file.length()
        if (length <= maxBytes) return file.readText()
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(length - maxBytes)
            val bytes = ByteArray(maxBytes.toInt())
            raf.readFully(bytes)
            // Drop everything up to and including the first newline (a partial line).
            return String(bytes).substringAfter('\n')
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

    private companion object {
        const val MAX_BYTES = 256L * 1024 // read at most the last 256 KB
        const val MAX_EVENTS = 400         // render at most the last 400 events
    }
}
