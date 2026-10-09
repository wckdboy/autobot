# kotlinx.serialization wire DTOs (generated serializers are looked up via Companion.serializer()).
-keepattributes *Annotation*, InnerClasses, Signature
-keep,includedescriptorclasses class dev.wckdboy.autobot.providers.remote.internal.**$$serializer { *; }
-keepclassmembers class dev.wckdboy.autobot.providers.remote.internal.** {
    *** Companion;
}
-keepclasseswithmembers class dev.wckdboy.autobot.providers.remote.internal.** {
    kotlinx.serialization.KSerializer serializer(...);
}
