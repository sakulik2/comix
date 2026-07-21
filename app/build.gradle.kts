plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// 手动注册 testClasses 任务桩
// 现代 AGP 已经剥离了此任务，但部分 IDE 插件依然会调用它
tasks.register("testClasses") {
    group = "verification"
    description = "Stub for missing testClasses task in modern AGP versions"
}

android {
    namespace = "xyz.sakulik.comic"
    compileSdk = 37

    defaultConfig {
        applicationId = "xyz.sakulik.comic"
        minSdk = 24
        targetSdk = 34
        versionCode = 17
        versionName = "1.7.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a"))
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // 解决第三方 JNI 库无法进行二次 strip 的编译警告
            keepDebugSymbols.add("**/libandroidx.graphics.path.so")
            keepDebugSymbols.add("**/lib7-Zip-JBinding.so")
            keepDebugSymbols.add("**/libdatastore_shared_counter.so")
        }
    }
}

// 直接遍历所有 Kotlin 编译任务，强制注入 JVM 目标
// 这样即使 android { ... } 闭包不识别 kotlinOptions，这里也能生效
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.11.0")


    // 图片加载
    implementation("io.coil-kt:coil-compose:2.7.0")

    // CBR 解析库
    implementation("com.github.junrar:junrar:7.6.0")
    // 对于 RAR5 (CBR v5) 的原生 JNI 桥接支持
    implementation("com.github.omicronapps:7-Zip-JBinding-4Android:Release-16.02-2.03")

    // 携程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Room 数据库
    val roomVersion = "2.8.4"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // DocumentFile 增强
    implementation("androidx.documentfile:documentfile:1.1.0")

    // DataStore (用于代替 SharedPreferences 存储 API Key)
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    // Retrofit & OkHttp (双源联网底层框架)
    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-gson:3.0.0")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation("com.squareup.okhttp3:logging-interceptor:5.4.0")

    // ==== 跨进程后台工作架构 ====
    implementation("androidx.work:work-runtime-ktx:2.11.2")

    // ==== Jetpack Navigation Compose 与类型安全防脱序列化 ====
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // ==== XML 解析 (ComicInfo.xml) ====
    implementation("org.simpleframework:simple-xml:2.7.1") {
        exclude(group = "stax", module = "stax-api")
        exclude(group = "xpp3", module = "xpp3")
    }

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // 测试桩依赖（恢复此部分以防 AS 触发 testClasses 任务抛错）
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}

ksp {
    arg("room.generateKotlin", "true")
}
