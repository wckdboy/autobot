# SQLCipher: JNI looks up these classes/members by name from native code.
-keep class net.zetetic.database.** { *; }
-keep interface net.zetetic.database.** { *; }
-dontwarn net.zetetic.database.**

# Room: generated *_Impl classes are instantiated reflectively via their no-arg constructor.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class dev.wckdboy.autobot.core.data.db.AutobotDatabase_Impl { *; }
