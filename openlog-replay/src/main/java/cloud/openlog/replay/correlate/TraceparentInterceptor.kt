package cloud.openlog.replay.correlate

import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp interceptor that injects a fresh W3C `traceparent` header (and the
 * session id) on each outgoing request, tying host network calls to the replay
 * session (SPEC.md T8).
 *
 * OkHttp is a `compileOnly` dependency; the host application supplies it at
 * runtime and installs this interceptor on its own client:
 *
 * ```
 * client.newBuilder().addInterceptor(OpenLog.traceInterceptor()).build()
 * ```
 */
class TraceparentInterceptor(
    private val correlation: Correlation,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header(Correlation.HEADER, correlation.newTraceparent())
            .header("x-openlog-session", correlation.sessionId)
            .build()
        return chain.proceed(request)
    }
}
