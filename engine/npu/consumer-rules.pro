# JNI: libautobot_npu.so exports Java_dev_wckdboy_autobot_engine_npu_NativeNpu_*, so the class name
# and its native method names must survive R8.
-keepclasseswithmembernames,includedescriptorclasses class dev.wckdboy.autobot.engine.npu.NativeNpu {
    native <methods>;
}

# Native code looks these callbacks up by name (GetMethodID), so they must not be renamed or removed.
-keep interface dev.wckdboy.autobot.engine.npu.NativeNpu$Sink { *; }
-keepclassmembers class * implements dev.wckdboy.autobot.engine.npu.NativeNpu$Sink {
    void onStatus(java.lang.String);
    void onProgress(int, int);
    void onImage(int, java.lang.String, int, int, long);
}
