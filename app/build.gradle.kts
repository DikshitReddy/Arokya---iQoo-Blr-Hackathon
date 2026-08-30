import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.arokya.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.arokya.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.8.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // ---- Camera scan (real CameraX preview + capture) ----
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // ---- Proactive nudge scheduling (nudge/NudgeWorker.kt) ----
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // ---- ML upgrade path (uncomment when you wire real models) ----
    // implementation("com.google.mlkit:barcode-scanning:17.3.0")
    // implementation("com.google.mlkit:text-recognition:16.0.1")

    // ---- LiteRT-LM: on-device chat + vision (Gemma3n .litertlm model) ----
    // https://github.com/google-ai-edge/LiteRT-LM/blob/main/docs/api/kotlin/getting_started.md
    implementation("com.google.ai.edge.litertlm:litertlm-android:latest.release")

    // ---- Persistent on-device database: plain SQLiteOpenHelper (data/db/) —
    // no Room. Room's kapt/KSP codegen can't read this project's Kotlin 2.4.x
    // metadata (a hard tooling-version wall, not a config issue), and Kotlin
    // 2.4.10 is required for LiteRT-LM above. Sensitive fields (profile PII,
    // lab findings) are individually encrypted — see data/db/FieldCrypto.kt.
}
