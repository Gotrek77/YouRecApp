// One module, no dependencies: the app is written against the Android
// framework alone. Sources live flat in YouRec/, like the iPhone app's.
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
    }
    sourceSets["main"].apply {
        manifest.srcFile("AndroidManifest.xml")
        java.setSrcDirs(listOf("YouRec"))
        res.setSrcDirs(listOf("res"))
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
