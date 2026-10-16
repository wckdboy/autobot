# JNI: libautobot_llama.so exports Java_dev_wckdboy_autobot_engine_llama_NativeLlama_*, so the class
# name and its native method names must survive R8.
-keepclasseswithmembernames,includedescriptorclasses class dev.wckdboy.autobot.engine.llama.NativeLlama {
    native <methods>;
}

# Native code looks this callback up by name (GetMethodID), so it must not be renamed or removed.
-keep interface dev.wckdboy.autobot.engine.llama.NativeLlama$DeltaSink { *; }
-keepclassmembers class * implements dev.wckdboy.autobot.engine.llama.NativeLlama$DeltaSink {
    void onDelta(int, byte[]);
}
