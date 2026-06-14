package cloud.openlog.replay.net

import java.net.HttpURLConnection
import java.net.URL

/**
 * Default transport, backed by [HttpURLConnection]. No third-party dependency, so
 * it always works — including in a cold WorkManager process before the host app
 * has initialized anything. Stateless; safe to share.
 */
object UrlConnectionTransport : HttpTransport {

    override fun execute(request: HttpRequest): HttpResponse {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
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
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { it.readBytes() } ?: ByteArray(0)
            return HttpResponse(code, body)
        } finally {
            connection?.disconnect()
        }
    }
}
