# --- 通用 Android 配置 ---
-keepattributes Signature, Exceptions, *Annotation*
-dontwarn android.test.**
-dontwarn android.support.**

# --- Room 数据库支持 ---
-keepclassmembers class * extends androidx.room.RoomDatabase {
    <init>(...);
}
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# --- Retrofit & OkHttp (网络层) ---
-keepattributes Signature, InnerClasses, EnclosingMethod
-keep class retrofit2.** { *; }
-keep interface retrofit2.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**

# --- Gson (JSON 解析) ---
-keep class com.google.gson.** { *; }
-keep class xyz.sakulik.comic.model.** { *; }

# --- Kotlin Serialization (类型安全导航) ---
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class xyz.sakulik.comic.navigation.** { *; }

# --- Coil (图片加载) ---
-keep class coil.** { *; }
-dontwarn coil.**

# --- Junrar (CBR 解析) ---
-keep class com.github.junrar.** { *; }
-dontwarn com.github.junrar.**

# --- SLF4J (修复 R8 Missing class 错误) ---
-dontwarn org.slf4j.**

# --- WorkManager (修复反射实例化失败) ---
-keep class androidx.work.OverwritingInputMerger { *; }
-keep class androidx.work.ArrayCreatingInputMerger { *; }
-keep class androidx.work.multiprocess.** { *; }

# --- Kotlin 协程 ---
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.android.HandlerContext {
    private abstract void handler;
}

# --- 保持所有的实体类 (Entity) 与数据模型 ---
-keep class xyz.sakulik.comic.model.db.** { *; }
-keep class xyz.sakulik.comic.viewmodel.** { *; }
