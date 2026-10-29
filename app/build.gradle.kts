plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "ca.mrklar.outlander.phevspy"
    compileSdk = 37

    defaultConfig {
        applicationId = "ca.mrklar.outlander.phevspy"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-m0"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(project(":protocol"))
    implementation(project(":transport-android"))
    debugImplementation(libs.androidx.compose.ui.tooling.preview)
}
