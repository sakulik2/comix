# comix

## ⚙️ 环境要求
- **Android Studio**: Ladybug (2024.2.1) 或更高版本
- **JDK**: 17 (推荐使用 Android Studio 自带的 jbr-17)
- **Gradle**: 9.4.1 (Kotlin DSL)

## 🛠️ 构建步骤
1. **同步项目**：
   使用 Android Studio 打开项目，等待 Gradle 同步完成。
2. **KSP 生成**：
   首次编译请先运行 `Build` -> `Make Project`，以生成 Room 数据库和相关的 KSP 代码。
3. **API Key 配置**（可选，用于元数据刮削）：
   进入 App -> 设置 -> 系统设置，填入您的 ComicVine API Key。
4. **运行**：
   选择目标设备并运行。首次进入请授权扫描本地漫画文件夹。

## 📦 核心依赖
- Compose (UI)
- Room (Database)
- Navigation (Type-safe Routes)
- KSP (Code Generation)
