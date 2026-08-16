plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}

android {
    namespace = "com.example.personalmemoryai"

    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.personalmemoryai"

        minSdk = 24
        targetSdk = 35

        versionCode = 2
        versionName = "0.2"
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
     aaptOptions {
        noCompress("tflite")

    }
}

dependencies {

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    implementation("com.google.android.material:material:1.11.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // English / Latin OCR
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // Arabic / multilingual OCR
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
    implementation("com.google.mediapipe:tasks-vision:0.10.35")
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
}
