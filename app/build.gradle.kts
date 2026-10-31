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

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("OUTLANDER_UPLOAD_KEYSTORE")
            val keystorePassword = System.getenv("OUTLANDER_UPLOAD_KEYSTORE_PASSWORD")
            val uploadKeyAlias = System.getenv("OUTLANDER_UPLOAD_KEY_ALIAS")
            val uploadKeyPassword = System.getenv("OUTLANDER_UPLOAD_KEY_PASSWORD")
            if (listOf(keystorePath, keystorePassword, uploadKeyAlias, uploadKeyPassword).all { !it.isNullOrBlank() }) {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = uploadKeyAlias
                keyPassword = uploadKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            val uploadSigningEnv = listOf(
                "OUTLANDER_UPLOAD_KEYSTORE",
                "OUTLANDER_UPLOAD_KEYSTORE_PASSWORD",
                "OUTLANDER_UPLOAD_KEY_ALIAS",
                "OUTLANDER_UPLOAD_KEY_PASSWORD",
            )
            if (uploadSigningEnv.all { !System.getenv(it).isNullOrBlank() }) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            isShrinkResources = false
        }
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
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(project(":protocol"))
    implementation(project(":transport-android"))
    implementation(libs.androidx.compose.ui.tooling.preview)
    testImplementation(kotlin("test-junit5"))
}

tasks.withType<Test> { useJUnitPlatform() }
