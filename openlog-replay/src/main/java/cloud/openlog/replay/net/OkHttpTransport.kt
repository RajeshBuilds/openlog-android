package cloud.openlog.replay.net

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * [HttpTransport] backed by a host-supplied [OkHttpClient], so replay uploads reuse
 * the host's connection pool, TLS/cert-pinning, proxy, and interceptors.
 *
 * OkHttp is a `compileOnly` dependency of the SDK (golden rule #8 / the host
 * provides it). This class only class-loads when a host references it — i.e. a
 * host that already has OkHttp on its classpath — so a non-OkHttp host that never
 * sets `Config.transport` is unaffected and incurs no OkHttp dependency.
 *
 * Usage:
 * ```
 * OpenLog.init(context, OpenLog.Config(
 *     http = HttpSessionSink.Config(baseUrl = "https://openlog.sh", token = "..."),
 *     transport = OkHttpTransport(myOkHttpClient),
 * ))
 * ```
 */
class OkHttpTransport(private val client: OkHttpClient) : HttpTransport {

    override fun execute(request: HttpRequest): HttpResponse {
        // The body carries its own media type; avoid also setting a Content-Type
        // header (OkHttp would reject the duplicate).
        val contentType = request.headers["Content-Type"]?.toMediaTypeOrNull()
        val body = request.body.toRequestBody(contentType)

        val builder = Request.Builder().url(request.url)
        request.headers.forEach { (k, v) ->
            if (!k.equals("Content-Type", ignoreCase = true)) builder.header(k, v)
        }
        builder.method(request.method, body)

        client.newCall(builder.build()).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            return HttpResponse(response.code, bytes)
        }
    }
}
