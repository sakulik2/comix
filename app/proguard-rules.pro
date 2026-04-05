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
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
# 显式保持所有路由类及其成员，防止混淆导致 restored ID 不匹配
-keep class xyz.sakulik.comic.navigation.** { *; }
-keepclassmembers class xyz.sakulik.comic.navigation.** { *; }

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

# --- SevenZipJBinding (CBR v5 NIO) ---
-keep class net.sf.sevenzipjbinding.** { *; }
-keep interface net.sf.sevenzipjbinding.** { *; }
-dontwarn net.sf.sevenzipjbinding.**

# --- Compose 动画与手势加固 ---
-keep class androidx.compose.animation.** { *; }
-keep class androidx.compose.foundation.gestures.** { *; }
-keep class androidx.compose.ui.graphics.** { *; }
-keep class androidx.compose.foundation.layout.BoxScope { *; }
