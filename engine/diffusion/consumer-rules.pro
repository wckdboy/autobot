# JNI: libautobot_sd.so exports Java_dev_wckdboy_autobot_engine_diffusion_NativeSd_*, so the class
# name and its native method names must survive R8.
-keepclasseswithmembernames,includedescriptorclasses class dev.wckdboy.autobot.engine.diffusion.NativeSd {
    native <methods>;
}

# Native code looks these callbacks up by name (GetMethodID), so they must not be renamed or removed.
-keep interface dev.wckdboy.autobot.engine.diffusion.NativeSd$Sink { *; }
-keepclassmembers class * implements dev.wckdboy.autobot.engine.diffusion.NativeSd$Sink {
    void onProgress(int, int);
    void onPreview(byte[], int, int, int);
    void onImage(int, byte[], int, int, int, long);
}
