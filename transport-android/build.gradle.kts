plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "ca.mrklar.outlander.transport"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
    }
}

dependencies {
    api(project(":protocol"))
}
