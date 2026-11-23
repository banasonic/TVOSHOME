import java.net.HttpURLConnection
import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tvoshome.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tvoshome.app"
        minSdk = 28
        targetSdk = 28
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// OCR library (optional): use the first candidate that is really published on Maven Central.
// If none is found the app still builds and only the "read video text" (OCR) feature is unavailable.
fun urlExists(u: String): Boolean = try {
    val c = URL(u).openConnection() as HttpURLConnection
    c.requestMethod = "HEAD"
    c.connectTimeout = 8000
    c.readTimeout = 8000
    val ok = c.responseCode in 200..299
    c.disconnect()
    ok
} catch (e: Exception) { false }

val central = "https://repo.maven.apache.org/maven2/"
val ocrCandidates = listOf(
    "cz.adaptech.tesseract4android:tesseract4android:4.7.0" to central + "cz/adaptech/tesseract4android/tesseract4android/4.7.0/tesseract4android-4.7.0.pom",
    "cz.adaptech:tesseract4android:4.7.0" to central + "cz/adaptech/tesseract4android/4.7.0/tesseract4android-4.7.0.pom",
    "com.rmtheis:tess-two:9.1.0" to central + "com/rmtheis/tess-two/9.1.0/tess-two-9.1.0.pom"
)
val ocrDep = ocrCandidates.firstOrNull { urlExists(it.second) }
println("OCR dependency: " + (ocrDep?.first ?: "NONE (OCR disabled in this build)"))

// Offline speech recognition (Vosk): same idea, optional.
val voskCandidates = listOf(
    "com.alphacephei:vosk-android:0.3.47" to central + "com/alphacephei/vosk-android/0.3.47/vosk-android-0.3.47.pom",
    "com.alphacephei:vosk-android:0.3.45" to central + "com/alphacephei/vosk-android/0.3.45/vosk-android-0.3.45.pom",
    "com.alphacephei:vosk-android:0.3.47" to "https://alphacephei.com/maven/com/alphacephei/vosk-android/0.3.47/vosk-android-0.3.47.pom",
    "com.alphacephei:vosk-android:0.3.45" to "https://alphacephei.com/maven/com/alphacephei/vosk-android/0.3.45/vosk-android-0.3.45.pom"
)
val voskDep = voskCandidates.firstOrNull { urlExists(it.second) }
val jnaOk = urlExists(central + "net/java/dev/jna/jna/5.13.0/jna-5.13.0.aar")
println("Vosk dependency: " + (voskDep?.first ?: "NONE (audio translate disabled in this build)") + " | jna aar: " + jnaOk)

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.webkit:webkit:1.11.0")
    if (ocrDep != null) implementation(ocrDep.first)
    if (voskDep != null && jnaOk) {
        implementation(voskDep.first) { exclude(group = "net.java.dev.jna", module = "jna") }
        implementation("net.java.dev.jna:jna:5.13.0@aar")
    }
}
