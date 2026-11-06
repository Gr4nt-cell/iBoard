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
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        externalNativeBuild { cmake { cppFlags += "-std=c++17" } }
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")

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
                // Missing secrets arrive as empty strings, so fall back to sensible defaults.
                val storePass = System.getenv("UPLOAD_STORE_PASSWORD")?.trim().orEmpty()
                storePassword = storePass
                keyAlias = System.getenv("UPLOAD_KEY_ALIAS")?.trim()?.ifBlank { null } ?: "upload"
                keyPassword = System.getenv("UPLOAD_KEY_PASSWORD")?.trim()?.ifBlank { null } ?: storePass
            }
        }
    }

    ndkVersion = "28.2.13676358"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }

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
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // On-device translation and language detection (models downloaded once, then offline).
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")
}
