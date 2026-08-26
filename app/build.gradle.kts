plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.x3.klax"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.x3.klax"
        minSdk = 29
        targetSdk = 35
        versionCode = 5
        versionName = "2.3"
    }

    // Field guide §2/§11: never recompress audio or the MediaPipe model,
    // or SoundPool/HandLandmarker silently fail.
    androidResources {
        noCompress += listOf("ogg", "wav", "mp3", "task")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

// Name the built APK x3klax.apk (per-variant output directories keep
// debug and release builds apart).
android.applicationVariants.all {
    outputs.all {
        (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl)
            .outputFileName = "x3klax.apk"
    }
}

dependencies {
    // Camera/MediaPipe hand tracking removed — paddle is temple-pad swipe only.
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
}
