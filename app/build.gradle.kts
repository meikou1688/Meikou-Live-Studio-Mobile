plugins {
    id("com.android.application") version "8.13.2"
    id("org.jetbrains.kotlin.android") version "2.2.21"
}
android {
    namespace = "tw.meikou.live"
    compileSdk = 36
    defaultConfig {
        applicationId = "tw.meikou.live"
        minSdk = 30
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"
    }
}
