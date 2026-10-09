# Native code looks these callbacks up by name (GetMethodID), so they must not be renamed or removed.
-keep interface dev.wckdboy.autobot.engine.diffusion.NativeSd$Sink { *; }
-keepclassmembers class * implements dev.wckdboy.autobot.engine.diffusion.NativeSd$Sink {
    void onProgress(int, int);
    void onPreview(byte[], int, int, int);
    void onImage(int, byte[], int, int, int, long);
}
