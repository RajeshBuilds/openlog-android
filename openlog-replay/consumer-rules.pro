# kotlinx-serialization generated serializers must be kept for the wire model.
-keepclassmembers class cloud.openlog.replay.wire.** {
    *** Companion;
}
-keepclasseswithmembers class cloud.openlog.replay.wire.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class cloud.openlog.replay.wire.**$$serializer { *; }
