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
        versionCode = 4
        versionName = "0.1.3"
    }
}

dependencies { implementation("com.google.android.gms:play-services-auth:21.4.0") }
