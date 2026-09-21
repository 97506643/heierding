import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "h.heiErDing"
    compileSdk = 37

    val autoVersionCode = System.getenv("HED_VERSION_CODE")?.toIntOrNull() ?: 1
    val autoVersionName = System.getenv("HED_VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "1.0.0"

    defaultConfig {
        applicationId = "h.heiErDing"
        minSdk = 27
        targetSdk = 37
        versionCode = autoVersionCode
        versionName = autoVersionName
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        // 如果你的仓库有 app/keystore/release.jks，则通过环境变量启用 release 签名；
        // 否则 release 会退回使用 debug 签名，方便本地直接编译出可安装的 APK。
        create("release") {
            val ksFile = file("keystore/release.jks")
            val storePwd = providers.environmentVariable("HED_STORE_PASSWORD").orNull
            val alias = providers.environmentVariable("HED_KEY_ALIAS").orNull
            val keyPwd = providers.environmentVariable("HED_KEY_PASSWORD").orNull
            if (ksFile.isFile && storePwd != null && alias != null && keyPwd != null) {
                storeFile = ksFile
                storePassword = storePwd
                keyAlias = alias
                keyPassword = keyPwd
            }
        }
    }

    buildTypes {
        release {
            val ksFile = file("keystore/release.jks")
            val hasReleaseKey = ksFile.isFile &&
                providers.environmentVariable("HED_STORE_PASSWORD").orNull != null
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        // 设置页已改为纯 Android View 实现（MiuixSettingsPage），
        // 全模块不再引用 Compose 运行时，此处关闭 compose 以缩小产物。
        compose = false
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "DebugProbesKt.bin"
            excludes += "META-INF/*.version"
            excludes += "META-INF/com/android/build/gradle/app-metadata.properties"
            excludes += "META-INF/version-control-info.textproto"
            excludes += "META-INF/**/LICENSE"
            excludes += "META-INF/**/LICENSE.txt"
            excludes += "META-INF/**/NOTICE"
            excludes += "META-INF/**/NOTICE.txt"
            excludes += "kotlin/**"
            excludes += "kotlin-tooling-metadata.json"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")

    implementation("io.github.billywei01:fastkv:3.0.1")
    implementation(files("libs/dexkit-2.2.0-76551eb.aar"))
    implementation("com.google.flatbuffers:flatbuffers-java:23.5.26")
    implementation("com.highcapable.kavaref:kavaref-core:1.1.0")
    // kavaref-core 的 apiElements 不含 extension（仅 runtimeElements 传递），若后续需要
    // makeAccessible/self 等扩展函数，需显式依赖；当前工程使用本地扩展，此处显式声明以消除解析不确定性。
    implementation("com.highcapable.kavaref:kavaref-extension:1.1.0")
}

// 构建完成后自动复制 APK 到 dist/
tasks.register<Copy>("copyToDist") {
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    into(rootProject.layout.projectDirectory.dir("dist"))
    rename { providers.environmentVariable("HED_APK_NAME").orElse("heiErDing-release.apk").get() }
}
tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy("copyToDist")
}
