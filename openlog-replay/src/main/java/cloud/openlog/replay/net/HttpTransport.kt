package cloud.openlog.replay.net

import java.io.IOException

/**
 * Pluggable HTTP transport for replay uploads.
 *
 * The SDK ships a zero-dependency default ([UrlConnectionTransport], backed by
 * [java.net.HttpURLConnection]) so it never forces a networking library onto a
 * host app. A host that already runs OkHttp — and wants its connection pool,
 * TLS/cert-pinning, proxy, or interceptors reused — can supply
 * `OpenLog.Config(transport = OkHttpTransport(client))` and the upload worker
 * routes its API calls through that instead.
 *
 * Implementations MUST be safe to call from a background WorkManager thread and
 * may be invoked from a cold process (after the recording app was killed).
 */
fun interface HttpTransport {
    /**
     * Perform [request] and return the response. Throw [IOException] for transport
     * failures (connect/read timeouts, DNS, TLS) — the worker treats a thrown call
     * as a retryable network error. A non-2xx HTTP status is NOT a failure: return
     * it as [HttpResponse.code] so the worker can apply the ingest retry rules.
     */
    @Throws(IOException::class)
    fun execute(request: HttpRequest): HttpResponse
}

/**
 * A single HTTP request. [headers] includes `Content-Type` and (for API calls)
 * `Authorization`; transports must send them verbatim.
 */
class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: ByteArray,
)

/** An HTTP response. [body] is the raw bytes (used to parse the presign reply). */
class HttpResponse(
    val code: Int,
    val body: ByteArray,
)
