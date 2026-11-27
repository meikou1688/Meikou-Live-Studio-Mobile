plugins {
    id("com.android.application") version "8.13.2"
    id("org.jetbrains.kotlin.android") version "2.2.21"
}
android {
    namespace = "tw.meikou.live"
    compileSdk = 36
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    defaultConfig {
        applicationId = "tw.meikou.live"
        testInstrumentationRunner = "tw.meikou.live.BackgroundCheck"
        minSdk = 30
        targetSdk = 35
        versionCode = 10
        versionName = "0.1.9"
        manifestPlaceholders["studioLabel"] = "Meikou Live Studio Mobile"
    }
    testBuildType = providers.gradleProperty("studioTestBuildType").getOrElse("debug")
    buildTypes {
        create("preview") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".beta"
            manifestPlaceholders["studioLabel"] = "Meikou Live Beta"
            matchingFallbacks += "debug"
        }
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-auth:21.4.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.github.pedroSG94.RootEncoder:srt:2.8.2")
}
