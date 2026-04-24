plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.packetcapture.xposed"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.packetcapture.xposed"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
    }
}

// 注意：不需要在这里再配置 repositories 块，已在 settings.gradle.kts 中统一管理

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.google.code.gson:gson:2.10.1")

    // Xposed API - 使用 compileOnly 确保不会打包到 APK
    // 如果官方仓库无法访问，可以切换到本地 jar 包方式
    compileOnly("de.robv.android.xposed:api:82")  // 使用更新的版本
    
    // 或者使用本地 jar 包（如果仓库不可用）
    // compileOnly(fileTree("libs") { include("*.jar") })
    
    // 备用：使用 Maven Central 上的版本（如果有）
    // compileOnly("de.robv.android.xposed:api:82") {
    //     isTransitive = false
    // }

    // Hook 目标（仅编译引用）
    compileOnly("com.squareup.okhttp3:okhttp:4.12.0")
    compileOnly("org.apache.httpcomponents:httpclient:4.5.14")
    
    // 添加 Android 测试依赖
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}