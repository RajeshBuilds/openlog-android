package cloud.openlog.replay.net

/**
 * Process-global holder for the active [HttpTransport].
 *
 * WorkManager constructs [cloud.openlog.replay.sink.ReplayUploadWorker]
 * reflectively, so the host-chosen transport can't be passed in directly — it's
 * registered here by `OpenLog.init` (called from `Application.onCreate`, which
 * always runs before any worker in the process, so the override is in place even
 * for a cold-process upload). If nothing was registered, uploads fall back to the
 * always-available [UrlConnectionTransport].
 */
object OpenLogTransports {

    @Volatile
    private var override: HttpTransport? = null

    /** Install the host-chosen transport (null clears it back to the default). */
    fun set(transport: HttpTransport?) {
        override = transport
    }

    /** The transport for OpenLog API calls (ingest / presign / commit). */
    fun current(): HttpTransport = override ?: UrlConnectionTransport
}
