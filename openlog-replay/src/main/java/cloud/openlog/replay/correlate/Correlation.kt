package cloud.openlog.replay.correlate

import java.security.SecureRandom
import java.util.UUID

/**
 * Session correlation (SPEC.md T8). Mints a `session_id` and a W3C trace id for
 * the session, produces `traceparent` headers, and best-effort mirrors the ids
 * into Crashlytics custom keys so a crash can be tied back to its replay.
 *
 * The actual OkHttp interceptor lives in [TraceparentInterceptor]; it is
 * compile-only against OkHttp (provided by the host app).
 */
class Correlation private constructor(
    val sessionId: String,
    val traceId: String,
) {
    private val random = SecureRandom()

    /** Mint a fresh W3C `traceparent` for one outgoing request (new span id). */
    fun newTraceparent(): String {
        val spanId = randomHex(8)
        // version "00", sampled flag "01"
        return "00-$traceId-$spanId-01"
    }

    private fun randomHex(bytes: Int): String {
        val buf = ByteArray(bytes)
        random.nextBytes(buf)
        return buf.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val HEADER = "traceparent"

        fun start(): Correlation {
            val sessionId = UUID.randomUUID().toString()
            val traceId = randomTraceId()
            val correlation = Correlation(sessionId, traceId)
            mirrorToCrashlytics(sessionId, traceId)
            return correlation
        }

        private fun randomTraceId(): String {
            val buf = ByteArray(16)
            SecureRandom().nextBytes(buf)
            return buf.joinToString("") { "%02x".format(it) }
        }

        /**
         * Set `session_id`/`trace_id` as Crashlytics custom keys via reflection so
         * the SDK has no hard dependency on Firebase. No-op if Crashlytics is absent.
         */
        private fun mirrorToCrashlytics(sessionId: String, traceId: String) {
            try {
                val clazz = Class.forName("com.google.firebase.crashlytics.FirebaseCrashlytics")
                val instance = clazz.getMethod("getInstance").invoke(null)
                val setKey = clazz.getMethod("setCustomKey", String::class.java, String::class.java)
                setKey.invoke(instance, "session_id", sessionId)
                setKey.invoke(instance, "trace_id", traceId)
            } catch (_: Throwable) {
                // Crashlytics not on the classpath — correlation keys are optional.
            }
        }
    }
}
