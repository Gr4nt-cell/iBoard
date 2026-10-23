plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gr4nt.iboard"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.gr4nt.iboard"
        minSdk = 26
        targetSdk = 36
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")

        // Offline dictation runs whisper.cpp natively; 64-bit ARM covers practically all current phones.
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared", "-DCMAKE_BUILD_TYPE=Release")
                cppFlags += listOf("-O3")
            }
        }
    }

    // Use the NDK already installed on the build machine (GitHub runners ship one).
    (System.getenv("ANDROID_NDK_LATEST_HOME") ?: System.getenv("ANDROID_NDK_HOME"))
        ?.takeIf { file(it).exists() }?.let { ndk ->
            ndkPath = ndk
            // Match ndkVersion to that NDK so AGP doesn't try to download another one.
            file("$ndk/source.properties").takeIf { it.exists() }?.readLines()
                ?.firstOrNull { it.startsWith("Pkg.Revision") }?.substringAfter("=")?.trim()
                ?.let { ndkVersion = it }
        }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        create("iboard") {
            storeFile = rootProject.file("keystore/iboard-debug.jks")
            storePassword = "iboard123"
            keyAlias = "iboard"
            keyPassword = "iboard123"
        }
        // Google Play upload key, only when provided through the environment (never committed).
        System.getenv("UPLOAD_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }?.let { path ->
            create("upload") {
                storeFile = file(path)
                storePassword = System.getenv("UPLOAD_STORE_PASSWORD")
                keyAlias = System.getenv("UPLOAD_KEY_ALIAS") ?: "upload"
                keyPassword = System.getenv("UPLOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("iboard")
        }
        release {
            // R8: shrink, optimise and obfuscate (Play's DEX optimisation check).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sideload APK: fixed iboard key. Play bundle: upload key if given, otherwise left unsigned
            // (IBOARD_UNSIGNED=1) so it can be signed with the upload key afterwards.
            signingConfig = when {
                signingConfigs.findByName("upload") != null -> signingConfigs.getByName("upload")
                System.getenv("IBOARD_UNSIGNED") == "1" -> null
                else -> signingConfigs.getByName("iboard")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
    androidResources {
        noCompress += listOf("txt", "tsv")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // On-device LLM for Rambler clean-up (model downloaded in the app, not bundled).
    implementation("com.google.mediapipe:tasks-genai:0.10.24")
}
