// One module, written against the Android framework. Sources live flat in
// YouRec/, like the iPhone app's. Native code: the iPhone app's own RNNoise
// (ThirdParty/RNNoise). One library: ONNX Runtime, which runs upstream
// DeepFilterNet3's ONNX graphs (Android/ThirdParty/DeepFilterNet3).
plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.0.21"
}

android {
    namespace = "com.antirez.yourec"
    compileSdk = 35
    defaultConfig {
        // For your own device build or a fork, use an identifier you own.
        applicationId = (findProperty("yourec.applicationId") as String?) ?: "com.antirez.yourec"
        minSdk = 29
        // 34: the screens place themselves around the system bars explicitly.
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    externalNativeBuild { cmake { path = file("native/CMakeLists.txt"); version = "3.22.1" } }
    sourceSets["main"].apply {
        manifest.srcFile("AndroidManifest.xml")
        java.setSrcDirs(listOf("YouRec"))
        res.setSrcDirs(listOf("res"))
        assets.srcDir(layout.buildDirectory.dir("generated/neural-assets"))
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    lint { abortOnError = false }
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.1")
}

// The networks and their DSP data, as assets: upstream DeepFilterNet3's ONNX
// graphs, and the iPhone app's DeepFilterNet3 filterbanks, read in place.
val neuralAssets by tasks.registering(Copy::class) {
    from("ThirdParty/DeepFilterNet3") { include("*.onnx"); into("DeepFilterNet3") }
    from("../ThirdParty/DeepFilterNet3/DeepFilterNet3-DSP.json") { into("DeepFilterNet3") }
    into(layout.buildDirectory.dir("generated/neural-assets"))
}
tasks.named("preBuild") { dependsOn(neuralAssets) }
