plugins {
    id("com.android.library")
}

android {
    namespace = "com.ninjha.still.aicore"
    compileSdk = 34

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta2")
}
