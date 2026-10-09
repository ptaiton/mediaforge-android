plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseVersion = providers.gradleProperty("VERSION_NAME")
    .orElse("0.1.0")
    .get()
val releaseVersionCode = providers.gradleProperty("VERSION_CODE")
    .orElse("1")
    .get()
    .toInt()
val signingFile = providers.gradleProperty("ANDROID_KEYSTORE_FILE")
val signingStorePassword = providers.gradleProperty("ANDROID_KEYSTORE_PASSWORD")
val signingKeyAlias = providers.gradleProperty("ANDROID_KEY_ALIAS")
val signingKeyPassword = providers.gradleProperty("ANDROID_KEY_PASSWORD")

android {
    namespace = "com.mediaforge.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mediaforge.android"
        minSdk = 26
        targetSdk = 35
        versionCode = releaseVersionCode
        versionName = releaseVersion
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

    signingConfigs {
        create("persistentRelease") {
            if (signingFile.isPresent && signingStorePassword.isPresent && signingKeyAlias.isPresent && signingKeyPassword.isPresent) {
                storeFile = file(signingFile.get())
                storePassword = signingStorePassword.get()
                keyAlias = signingKeyAlias.get()
                keyPassword = signingKeyPassword.get()
            }
        }
    }

    buildTypes {
        getByName("release") {
            if (signingFile.isPresent && signingStorePassword.isPresent && signingKeyAlias.isPresent && signingKeyPassword.isPresent) {
                signingConfig = signingConfigs.getByName("persistentRelease")
            }
        }
    }
}

dependencies {
    constraints {
        implementation("androidx.fragment:fragment:1.8.6") {
            because("The QR scanner must support the Activity Result API used by the companion.")
        }
    }
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250107")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.2.0")
    implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
    implementation("com.google.firebase:firebase-messaging")
}
