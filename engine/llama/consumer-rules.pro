# Native code looks this callback up by name (GetMethodID), so it must not be renamed or removed.
-keep interface dev.wckdboy.autobot.engine.llama.NativeLlama$DeltaSink { *; }
-keepclassmembers class * implements dev.wckdboy.autobot.engine.llama.NativeLlama$DeltaSink {
    void onDelta(int, byte[]);
}
