val inventoriAiBackendUrl = providers.gradleProperty("INVENTORI_AI_BACKEND_URL").orElse("https://inventori-api.barndai.com").get()
val escapedInventoriAiBackendUrl = inventoriAiBackendUrl.replace("\\", "\\\\").replace("\"", "\\\"")

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.inventori.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.inventori.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "2.3.0"
        buildConfigField("String", "AI_BACKEND_URL", "\"" + escapedInventoriAiBackendUrl + "\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        animationsDisabled = true
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
