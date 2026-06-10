# kotlinx-serialization generated serializers must be kept for the wire model.
-keepclassmembers class cloud.openlog.replay.wire.** {
    *** Companion;
}
-keepclasseswithmembers class cloud.openlog.replay.wire.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class cloud.openlog.replay.wire.**$$serializer { *; }

# androidx.fragment and OkHttp are OPTIONAL (compileOnly) dependencies. If a
# consumer minifies without them on the classpath, the guarded references must
# not fail the build — the code paths that touch them are gated at runtime.
-dontwarn androidx.fragment.app.**
-dontwarn okhttp3.**
