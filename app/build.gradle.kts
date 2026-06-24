plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jjjk.radioptt"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jjjk.radioptt"
        // These radios commonly ship Android 9-11; keep the floor low.
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.5-fix-spinner-v2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("io.livekit:livekit-android:2.10.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
}
