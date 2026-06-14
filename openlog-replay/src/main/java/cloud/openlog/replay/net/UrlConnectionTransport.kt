package cloud.openlog.replay.net

import java.net.HttpURLConnection
import java.net.URL

/**
 * Default transport, backed by [HttpURLConnection]. No third-party dependency, so
 * it always works — including in a cold WorkManager process before the host app
 * has initialized anything. Stateless; safe to share.
 *
 * Follows 301/302/307/308 redirects manually, **preserving the method and body**
 * (HttpURLConnection won't auto-follow 307/308, and downgrades POST→GET on
 * 301/302). This matters because deployments commonly 308-redirect apex→www or
 * http→https; without it a POST batch would come back as a 3xx and be dropped.
 */
object UrlConnectionTransport : HttpTransport {

    private const val MAX_REDIRECTS = 5
    private val REDIRECT_CODES = setOf(301, 302, 307, 308)

    override fun execute(request: HttpRequest): HttpResponse {
        var target = request.url
        var redirects = 0
        while (true) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(target).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false // we follow manually to keep method + body
                    requestMethod = request.method
                    doOutput = request.method == "POST" || request.method == "PUT"
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    request.headers.forEach { (k, v) -> setRequestProperty(k, v) }
                }
                connection.outputStream.use { it.write(request.body) }
                // responseCode triggers the request; an IOException here propagates and
                // the worker treats it as a retryable network error.
                val code = connection.responseCode

                val location = if (code in REDIRECT_CODES) connection.getHeaderField("Location") else null
                if (location != null && redirects < MAX_REDIRECTS) {
                    target = URL(URL(target), location).toString() // resolve relative Locations
                    redirects++
                    continue
                }

                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.use { it.readBytes() } ?: ByteArray(0)
                return HttpResponse(code, body)
            } finally {
                connection?.disconnect()
            }
        }
    }
}
