plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gr4nt.iboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gr4nt.iboard"
        minSdk = 26
        targetSdk = 34
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
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("iboard")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("iboard")
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
