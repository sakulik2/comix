// 根项目构建脚本 - 集中式插件声明管理
plugins {
    id("com.android.application") version "9.2.0" apply false
    id("com.google.devtools.ksp") version "2.3.11" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10" apply false
}
