# JNI: libautobot_whisper.so exports Java_dev_wckdboy_autobot_engine_speech_NativeWhisper_*, so the
# class name and its native method names must survive R8. Native code calls nothing back in Java.
-keepclasseswithmembernames,includedescriptorclasses class dev.wckdboy.autobot.engine.speech.NativeWhisper {
    native <methods>;
}
