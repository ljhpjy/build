plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 签名：CI 通过 -Pfuckds.storeFile=... 传入；没传就用 debug 签名（也能直接安装）。
val keystorePath = (findProperty("fuckds.storeFile") as String?) ?: System.getenv("FUCKDS_STORE_FILE")
val keystorePassword = (findProperty("fuckds.storePassword") as String?) ?: System.getenv("FUCKDS_STORE_PASSWORD")
val keystoreAlias = (findProperty("fuckds.keyAlias") as String?) ?: System.getenv("FUCKDS_KEY_ALIAS") ?: "fuckds"
val keystoreKeyPassword = (findProperty("fuckds.keyPassword") as String?) ?: System.getenv("FUCKDS_KEY_PASSWORD")

android {
    namespace = "com.fuckds.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fuckds.android"
        minSdk = 26
        // 关键：targetSdk 28 和 Termux 一样，Android 10+ 才允许 exec 应用私有目录里的
        // Node / ds-pow 可执行文件（targetSdk >= 29 会被 W^X 限制）。
        targetSdk = 28
        versionCode = (findProperty("fuckds.versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (findProperty("fuckds.versionName") as String?) ?: "1.0.0"
        resourceConfigurations += listOf("zh", "en")
    }

    flavorDimensions += "abi"
    productFlavors {
        create("arm64") {
            dimension = "abi"
            buildConfigField("String", "RUNTIME_ABI", "\"arm64-v8a\"")
        }
        create("x64") {
            dimension = "abi"
            buildConfigField("String", "RUNTIME_ABI", "\"x86_64\"")
        }
    }

    signingConfigs {
        if (!keystorePath.isNullOrBlank()) {
            create("release") {
                storeFile = rootProject.file(keystorePath)
                storePassword = keystorePassword
                keyAlias = keystoreAlias
                keyPassword = keystoreKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig =
                if (!keystorePath.isNullOrBlank()) signingConfigs.getByName("release")
                else signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    androidResources {
        // runtime.zip / fuckds.zip 已经压缩过，再压一遍只会拖慢构建
        noCompress += "zip"
    }

    lint {
        // targetSdk 28 会触发 ExpiredTargetSdkVersion，这是刻意的
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
}
