plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rumi.voiceassistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rumi.voiceassistant"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "1.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // ?? This tells Gradle to ignore the duplicate C++ library
    packaging {
        jniLibs {
            pickFirsts.add("**/libonnxruntime.so")
            pickFirsts.add("**/libc++_shared.so")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")

    // Sherpa-ONNX Native Engine
    implementation(files("libs/sherpa-onnx.aar"))

    // Java binding for the same ONNX Runtime used by the bundled Sherpa engine.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    // OkHttp for Network/LLM API calls
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
