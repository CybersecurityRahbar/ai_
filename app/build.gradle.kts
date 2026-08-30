import java.net.URI
import java.io.InputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}

val mobileClipTokenizerAssets = layout.projectDirectory.dir("src/main/assets/models/semantic/openclip")
val mobileClipTokenizerFiles = listOf("vocab.json", "merges.txt")
val mobileClipTokenizerRef = "a406a1bd0b882b27509e608f3cb199de52010c4d"
val mobileClipTokenizerUrls = mapOf(
    "vocab.json" to "https://huggingface.co/apple/MobileCLIP-S2-OpenCLIP/resolve/$mobileClipTokenizerRef/vocab.json?download=true",
    "merges.txt" to "https://huggingface.co/apple/MobileCLIP-S2-OpenCLIP/resolve/$mobileClipTokenizerRef/merges.txt?download=true"
)

val prepareMobileClipTokenizer by tasks.registering {
    outputs.files(mobileClipTokenizerFiles.map { mobileClipTokenizerAssets.file(it) })
    doLast {
        val dir = mobileClipTokenizerAssets.asFile
        dir.mkdirs()
        mobileClipTokenizerFiles.forEach { name ->
            val target = dir.resolve(name)
            if (target.exists() && target.length() > 0L) return@forEach
            val temp = dir.resolve(".$name.download")
            URI(mobileClipTokenizerUrls.getValue(name)).toURL().openStream().use { input: InputStream ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            if (temp.length() == 0L) error("Downloaded empty MobileCLIP tokenizer asset: $name")
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        }
        dir.resolve("PROVENANCE.txt").writeText(
            "MobileCLIP-S2 OpenCLIP tokenizer assets\n" +
                "source=apple/MobileCLIP-S2-OpenCLIP\n" +
                "revision=$mobileClipTokenizerRef\n" +
                "runtime=OpenAI/OpenCLIP SimpleTokenizer semantics\n",
            Charsets.UTF_8
        )
    }
}

tasks.named("preBuild").configure { dependsOn(prepareMobileClipTokenizer) }

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

    buildFeatures { viewBinding = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += "tflite" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")
    implementation("androidx.work:work-runtime-ktx:2.10.1")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
    implementation("com.google.mediapipe:tasks-vision:0.10.35")
    implementation("org.tensorflow:tensorflow-lite:2.16.1")

    implementation("com.google.mlkit:pose-detection:18.0.0-beta5")
    implementation("org.opencv:opencv:4.13.0")
}
