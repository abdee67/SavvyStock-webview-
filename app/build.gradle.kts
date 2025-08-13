plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.savvyy.stockmanagement"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.savvyy.stockmanagement"
        minSdk = 21
        targetSdk = 36
        versionCode = 8
        versionName = "1.7"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.14.0")
    implementation(libs.appcompat)
    implementation (libs.swiperefreshlayout)
    implementation("com.google.android.material:material:1.12.0")
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.media3.exoplayer)
    implementation(libs.shimmer)
    implementation(libs.firebase.crashlytics.buildtools)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}