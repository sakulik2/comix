# CLAUDE.md

comix：单模块 Android 漫画阅读器，包名 `xyz.sakulik.comic`。Kotlin + Jetpack Compose + Room + Retrofit。JDK 17，minSdk 24 / targetSdk 34 / compileSdk 37，ABI 只含 `armeabi-v7a` 和 `arm64-v8a`。功能说明与使用方式见 `README.md`。

## 构建与测试

JDK 17 是硬性要求（`compileOptions` 与 Kotlin `jvmTarget` 都锁在 17）。用仓库内的 Gradle wrapper，不要用系统 Gradle。

- 验证编译：`./gradlew assembleDebug`
- 跑 JVM 测试：`./gradlew test`
- 发版 APK 走 Android Studio：**Build > Generate App Bundles or APKs**

首次构建由 KSP 生成 Room 代码。仓库无 CI，也没有已提交的 `test` / `androidTest` 源码树（`app/build.gradle.kts` 里的 `testClasses` 桩任务和测试依赖只是为了让 IDE 不报错）。新增测试放 `app/src/test`（JVM）和 `app/src/androidTest`（设备），类名和方法名按被测行为命名，例如 `ComicNameParserTest`。优先覆盖解析、loader、数据库行为和 ViewModel 状态迁移。不要提交 `build/` 或 IDE 文件。

## 代码风格

Kotlin，四空格缩进，可读的地方用表达式函数体，公开 API 写明类型。遵守既有包边界。命名：类和 composable 用 `PascalCase`，函数、属性、参数用 `camelCase`，常量用 `UPPER_SNAKE_CASE`，资源文件小写加下划线（如 `ic_folder.xml`）。提交前跑 Android Studio 的 Kotlin 格式化和检查。

## 目录结构

```
app/src/main/java/xyz/sakulik/comic/
  ui/            Compose 页面；子包 bookshelf/ components/ scrape/ settings/ theme/
  viewmodel/     Bookshelf / Reader / Scrape / Main 四个 ViewModel
  model/db/      Room 实体、DAO、迁移
  model/loader/  CBZ、CBR、PDF、远程页面加载
  model/network/ 远程书库与元数据接口（bangumi/ comicvine/）
  model/scanner/ 目录扫描、封面提取、文件名解析
  model/preferences/  DataStore 与凭据加密
  model/processor/    图像处理
  navigation/    Routes.kt
  worker/        LibraryScanWorker
  di/            已废弃的空文件
app/src/debug/ , app/src/release/   变体专属代码
app/schemas/   已导出的 Room schema
```

## 需要留意的架构约定

- **没有 DI 框架。** `di/DatabaseModule.kt`、`di/NetworkModule.kt` 是空文件（AGP 9 与 Hilt 不兼容后被废弃），依赖全靠手写单例，例如 `AppDatabase.getDatabase(context)`。不要在这里加 Hilt/Koin。
- **Room schema 已导出。** 改动 `model/db/` 里的实体必须同时提升 `AppDatabase` 的 `version`、在 `DatabaseMigrations.ALL` 里加迁移，并提交 `app/schemas/` 下新生成的 JSON。当前 version 13。
- **构建变体有代码差异。** 自动更新功能只存在于 `app/src/release/`，`app/src/debug/` 是对应的空壳。改更新相关逻辑要同时看两个 source set。
- **Loader 按来源分派。** 本地压缩包 / PDF / 远程流三条路径由 `model/loader/ComicPageLoaderFactory.kt` 选择，`ComicPageLoader` 是共同接口；新格式从这里接入。
- **资源保护限制是有意的。** `ArchiveResourceLimits.kt`、`RemoteResourceLimits.kt` 限制条目数、单页大小和解码像素，别为了"支持更大的文件"直接放开。
- **凭据不落源码。** ComicVine API Key、Comix Token 由 `SettingsDataStore` + `CredentialCipher` 管理，任何时候不要写进代码或提交。

## 提交与 PR

提交信息沿用现有风格：短、小写、动作导向，一次提交一件事（例如 `add release update checks and downloads`、`harden remote access and large archive handling`）。PR 说明用户可见影响和架构影响，关联 issue，列出验证命令，UI 改动附截图或短录屏。数据库/schema、权限、API Key 处理的变更要单独点出来。
