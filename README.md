# comix

comix 是一个 Android 漫画阅读器，用于管理和阅读本地漫画文件，也可以连接配套的 `comix.js` 服务端读取远程书库。应用使用系统文件选择器访问漫画目录，不要求传统的全盘存储权限。

## 主要功能

- 扫描一个或多个本地目录，并在书架中显示封面、阅读进度和元数据。
- 支持 CBZ、ZIP、CBR、RAR、RAR5 和 PDF 文件。
- 提供单页、横屏双页和 Webtoon 纵向滚动模式。
- 支持从右向左翻页、音量键翻页、双击缩放和画质增强。
- 读取 `ComicInfo.xml`，并可在应用内编辑标题、系列、作者、分类等信息。
- 可选使用 ComicVine 或 Bangumi 搜索元数据。ComicVine 需要用户自己的 API Key。
- 可连接 `comix.js` 服务端，同步远程书架并按需读取页面。

## 系统与格式

最低系统版本为 Android 7.0（API 24）。当前构建包含 `armeabi-v7a` 和 `arm64-v8a`，不包含 x86 或 x86_64 原生库。

大体积漫画没有统一的 2GB 文件限制，但实际读取速度取决于设备、存储提供程序和剩余空间。部分不支持随机访问的文件来源可能需要建立临时缓存。压缩包中的条目数量、单页大小和解码后像素数仍受资源保护限制。

## 安装

可以从项目的 [Releases](https://github.com/sakulik2/comix/releases) 页面下载已经发布的 APK，也可以按照下方步骤在 Android Studio 中手动构建。安装非应用商店 APK 时，Android 可能会要求为文件管理器或浏览器授予“安装未知应用”权限。

## 基本使用

1. 打开应用，在书架页面添加漫画目录。
2. 在系统文件选择器中授予该目录的长期访问权限。
3. 等待后台扫描完成。以后可以使用书架顶部的刷新按钮重新扫描已授权目录。
4. 点击漫画开始阅读。阅读方向、模式、音量键翻页和画质增强可在阅读页面中调整。
5. 如需自动补充元数据，在设置中启用刮削功能，并按需填写 ComicVine API Key。

## 远程书库

远程阅读需要单独部署 [comix.js](https://github.com/sakulik2/comix.js)。在应用设置中启用云端书库，然后填写服务端基础地址和可选 Token。

局域网地址可以使用 HTTP，例如 `http://192.168.1.10:3000/`。公网地址必须使用 HTTPS，应用不会向公网 HTTP 地址发送 Comix Token。远程页面会按设备需要的宽度请求和缓存；服务端首次处理较大的漫画时可能需要等待几分钟。

## 构建

项目使用 JDK 17、Android Gradle Plugin 9.2.0、Gradle 9.4.1 和 Kotlin DSL。请使用支持当前 AGP 版本的 Android Studio 打开仓库根目录。

本项目只接受 Android Studio 手动构建，不使用命令行 Gradle 构建：

1. 等待 Android Studio 完成 Gradle 和 KSP 同步。
2. 使用 **Build > Make Project** 检查编译。
3. 使用 **Build > Generate App Bundles or APKs > Build APK(s)** 生成 APK。
4. 使用 Android Studio 的 **Run** 操作安装到设备或使用 ARM 系统镜像的模拟器。

首次构建会由 KSP 生成 Room 数据库相关代码。API Key、服务端 Token 和本地路径应在应用设置中配置，不要写入源码或提交到 Git。

## 项目结构

- `app/src/main/java/xyz/sakulik/comic/ui/`：Compose 页面和阅读组件。
- `app/src/main/java/xyz/sakulik/comic/viewmodel/`：书架、阅读器和元数据状态。
- `app/src/main/java/xyz/sakulik/comic/model/loader/`：CBZ、CBR、PDF 和远程页面加载。
- `app/src/main/java/xyz/sakulik/comic/model/db/`：Room 数据库、实体和迁移。
- `app/src/main/java/xyz/sakulik/comic/model/network/`：远程书库和元数据接口。
- `app/src/main/res/`：Android 资源与应用图标。

项目目前没有提交自动化构建流程。修改后应在 Android Studio 中完成同步和手动构建，并在真实设备上检查本地文件、远程书库和阅读手势。
