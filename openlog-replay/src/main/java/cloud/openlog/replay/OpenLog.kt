package cloud.openlog.replay

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import cloud.openlog.replay.capture.SessionCaptureEngine
import cloud.openlog.replay.correlate.Correlation
import cloud.openlog.replay.correlate.TraceparentInterceptor
import cloud.openlog.replay.graph.ViewScreenGraphProvider
import cloud.openlog.replay.mask.MaskPolicy
import cloud.openlog.replay.net.HttpTransport
import cloud.openlog.replay.net.OpenLogTransports
import cloud.openlog.replay.sink.CompositeSessionSink
import cloud.openlog.replay.sink.DeviceInfo
import cloud.openlog.replay.sink.FileSessionSink
import cloud.openlog.replay.sink.HttpSessionSink
import cloud.openlog.replay.sink.SessionSink
import java.io.File
import kotlin.math.roundToInt
import kotlin.random.Random
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Public entry point for the OpenLog Android Capture SDK.
 *
 * Lifecycle:
 * ```
 * OpenLog.init(context, OpenLog.Config(...))   // once, e.g. in Application.onCreate
 * OpenLog.setConsent(true)                      // explicit user consent (Part 4)
 * OpenLog.start()                               // begin capturing
 * ...
 * OpenLog.stop()                                // end the session
 * ```
 *
 * Capture is gated on **minSdk 26**, **explicit consent**, and **session-level
 * sampling** (Part 4). All capture work happens off the main thread.
 */
object OpenLog {

    /** SDK version reported to the ingest API as `X-OpenLog-Sdk`. */
    const val SDK_VERSION = "0.1.0"

    /**
     * @param maskAllText   mask all text by default (banking default: true).
     * @param maskAllImages mask all images by default (banking default: true).
     * @param sampleRate    fraction of sessions to record, 0.0..1.0.
     * @param throttleMs    minimum interval between snapshots of a window.
     * @param http          when set, events upload via [HttpSessionSink].
     * @param file          when true, events are also written to a local NDJSON file
     *                      (see [currentSessionFile]). Defaults to on only when not
     *                      uploading; combine with [http] for BOTH (upload + keep an
     *                      offline copy), or set false with [http] to upload only.
     *                      With neither, nothing is recorded.
     * @param transport     networking backend for replay uploads. Defaults to the
     *                      built-in [java.net.HttpURLConnection] transport (no host
     *                      dependency). Supply `OkHttpTransport(client)` to reuse a
     *                      host OkHttpClient's pool / TLS-pinning / proxy / interceptors.
     * @param captureScrolls capture scroll gestures as rrweb scroll events (source 3)
     *                      for smooth scroll playback. Main-thread cost is negligible
     *                      (throttled by [scrollThrottleMs]); adds recording volume
     *                      while scrolling.
     * @param captureInputs capture text/input changes in real time as rrweb input
     *                      events (source 5) instead of only at the next snapshot.
     *                      Input values are masked at source.
     * @param scrollThrottleMs minimum interval between scroll events per container.
     * @param debugClassNames DEBUG ONLY. When true, each wireframe also carries the
     *                      source view's platform class name in `Wireframe.className`
     *                      (e.g. `"MaterialButton"`) — raw-tree-style fidelity for
     *                      debugging capture issues. Off by default (adds volume).
     */
    data class Config(
        val maskAllText: Boolean = true,
        val maskAllImages: Boolean = true,
        val sampleRate: Double = 1.0,
        val throttleMs: Long = 1_000L,
        val http: HttpSessionSink.Config? = null,
        val file: Boolean = http == null,
        val transport: HttpTransport? = null,
        val captureScrolls: Boolean = true,
        val captureInputs: Boolean = true,
        val scrollThrottleMs: Long = 100L,
        val debugClassNames: Boolean = false,
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var appContext: Context? = null
    @Volatile private var config: Config = Config()
    @Volatile private var engine: SessionCaptureEngine? = null
    @Volatile private var correlation: Correlation? = null
    @Volatile private var sink: SessionSink? = null
    @Volatile private var consentGranted: Boolean = false

    /** Configure the SDK. Must be called before [start]. */
    @JvmStatic
    fun init(context: Context, config: Config = Config()) {
        this.appContext = context.applicationContext
        this.config = config
        // Register the upload transport globally so [ReplayUploadWorker] (built
        // reflectively by WorkManager) can reach it, even in a cold process.
        OpenLogTransports.set(config.transport)
    }

    /**
     * Record the user's consent decision. Capture only runs while consent is
     * granted; revoking consent stops the active session.
     */
    @JvmStatic
    fun setConsent(granted: Boolean) {
        consentGranted = granted
        if (!granted) stop()
    }

    /** The current session id, or null if not recording. */
    @JvmStatic
    fun sessionId(): String? = correlation?.sessionId

    /** True while a capture session is active. */
    @JvmStatic
    fun isRecording(): Boolean = engine != null

    /**
     * Start a capture session. No-op when already running, on API < 26, without
     * consent, or when sampled out.
     */
    @JvmStatic
    @Suppress("ObsoleteSdkInt") // explicit minSdk-26 guard per golden rule #7
    fun start() {
        val ctx = appContext ?: error("OpenLog.init(context) must be called before start()")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return // minSdk 26 guard (golden rule #7)
        if (engine != null) return
        if (!consentGranted) return
        if (Random.nextDouble() >= config.sampleRate) return

        val correlation = Correlation.start().also { this.correlation = it }
        val density = ctx.resources.displayMetrics.density
        val policy = MaskPolicy(config.maskAllText, config.maskAllImages)
        val sink = buildSink(ctx, correlation).also { this.sink = it }

        val captureEngine = SessionCaptureEngine(
            context = ctx,
            graphProvider = ViewScreenGraphProvider(includeClassNames = config.debugClassNames),
            policy = policy,
            sink = sink,
            correlation = correlation,
            density = density,
            throttleMs = config.throttleMs,
            captureScrolls = config.captureScrolls,
            captureInputs = config.captureInputs,
            scrollThrottleMs = config.scrollThrottleMs,
        )
        engine = captureEngine
        onMain { captureEngine.start() }
    }

    /** Stop the active capture session and flush the sink. */
    @JvmStatic
    fun stop() {
        val captureEngine = engine ?: return
        engine = null
        onMain { captureEngine.stop() }
    }

    /**
     * Block until queued capture work is written and the sink flushed, so the
     * session file can be read mid-session. No-op when not recording. Call off the
     * main thread.
     */
    @JvmStatic
    fun flush() {
        engine?.flushBlocking()
    }

    /**
     * The NDJSON file the current session is writing to, or null when not recording
     * or when the local file sink is disabled (`Config.file = false`, upload-only).
     * Pair with [flush] before reading.
     */
    @JvmStatic
    fun currentSessionFile(): File? = when (val s = sink) {
        is FileSessionSink -> s.file
        is CompositeSessionSink -> s.sinks.filterIsInstance<FileSessionSink>().firstOrNull()?.file
        else -> null
    }

    /**
     * An OkHttp interceptor that injects the session's W3C `traceparent` on each
     * request (T8). Install it on the host's OkHttpClient. It is inert (pass-through)
     * while no session is active. Requires OkHttp on the host classpath.
     */
    @JvmStatic
    fun traceInterceptor(): Interceptor = object : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val correlation = correlation ?: return chain.proceed(chain.request())
            return TraceparentInterceptor(correlation).intercept(chain)
        }
    }

    private fun buildSink(ctx: Context, correlation: Correlation): SessionSink {
        val sinks = buildList {
            config.http?.let {
                add(
                    HttpSessionSink(
                        context = ctx,
                        config = it,
                        sessionId = correlation.sessionId,
                        device = deviceInfo(ctx),
                        sdkVersion = SDK_VERSION,
                    ),
                )
            }
            if (config.file) {
                add(FileSessionSink(File(ctx.filesDir, "openlog/sessions"), correlation.sessionId))
            }
        }
        // One sink → use it directly; otherwise fan out (0 = no-op when both disabled).
        return sinks.singleOrNull() ?: CompositeSessionSink(sinks)
    }

    /** Device descriptor for the `X-OpenLog-Device` ingest header (sizes in dp). */
    private fun deviceInfo(ctx: Context): DeviceInfo {
        val metrics = ctx.resources.displayMetrics
        val appVersion = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
        return DeviceInfo(
            osVersion = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString(),
            manufacturer = Build.MANUFACTURER ?: "unknown",
            model = Build.MODEL ?: "unknown",
            density = metrics.density,
            w = (metrics.widthPixels / metrics.density).roundToInt(),
            h = (metrics.heightPixels / metrics.density).roundToInt(),
            appVersion = appVersion,
        )
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }
}
