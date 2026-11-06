plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.opw3.photos"
    compileSdk = 36

    defaultConfig {
        // Один APK и для телефона, и для часов: роль определяется по устройству (FEATURE_WATCH).
        // Одинаковые applicationId и подпись на обоих — условие работы Wear Data Layer.
        applicationId = "com.opw3.photos"
        minSdk = 30
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("androidx.activity:activity:1.9.3")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.wear:wear:1.3.0")
    implementation("androidx.wear:wear-remote-interactions:1.1.0")
}
