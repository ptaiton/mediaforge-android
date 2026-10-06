plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val mediaforgeUrl = providers.gradleProperty("MEDIAFORGE_URL")
    .orElse("http://10.0.2.2:8080/")
    .get()
    .let { if (it.endsWith("/")) it else "$it/" }
val releaseVersion = providers.gradleProperty("VERSION_NAME")
    .orElse("0.1.0")
    .get()

android {
    namespace = "com.mediaforge.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mediaforge.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = releaseVersion

        buildConfigField("String", "MEDIAFORGE_URL", "\"${mediaforgeUrl.replace("\"", "\\\"")}\"")
    }

    buildFeatures {
        buildConfig = true
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
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
}
