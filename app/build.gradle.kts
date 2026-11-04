import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing comes only from the environment (CI secrets). There is no
// fallback: a release build without credentials fails instead of using a debug key.
val keystorePath: String? = System.getenv("RELEASE_KEYSTORE_FILE")
val keystorePassword: String? = System.getenv("RELEASE_KEYSTORE_PASSWORD")
val releaseKeyAlias: String? = System.getenv("RELEASE_KEY_ALIAS")
val releaseKeyPassword: String? = System.getenv("RELEASE_KEY_PASSWORD")
val signingReady = !keystorePath.isNullOrBlank() && File(keystorePath).isFile &&
    !keystorePassword.isNullOrBlank() && !releaseKeyAlias.isNullOrBlank() && !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "com.glassescompanion.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.glassescompanion.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 100
        versionName = "1.0.0"
    }

    signingConfigs {
        if (signingReady) {
            create("release") {
                storeFile = File(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (signingReady) signingConfigs.getByName("release") else null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

gradle.taskGraph.whenReady {
    val wantsRelease = allTasks.any { it.project.name == "app" && it.name.contains("Release") && (it.name.startsWith("assemble") || it.name.startsWith("package") || it.name.startsWith("bundle")) }
    if (wantsRelease && !signingReady) {
        throw GradleException(
            "Release signing credentials missing (RELEASE_KEYSTORE_FILE, RELEASE_KEYSTORE_PASSWORD, " +
                "RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD). Refusing to build an unsigned or debug-signed release.",
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("io.github.webrtc-sdk:android:125.6422.07")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
