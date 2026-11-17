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
    val c = java.net.URL(u).openConnection() as java.net.HttpURLConnection
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

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.webkit:webkit:1.11.0")
    if (ocrDep != null) implementation(ocrDep.first)
}
