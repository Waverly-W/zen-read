plugins {
    alias(libs.plugins.zenread.android.application)
    alias(libs.plugins.zenread.android.compose)
    alias(libs.plugins.zenread.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.zenread.app"

    defaultConfig {
        applicationId = "com.zenread.reader"
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":feature:library"))
    implementation(project(":feature:reader"))
    implementation(project(":feature:notes"))
    implementation(project(":feature:settings"))
    implementation(project(":data:ai"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.compose.material3.navigation.suite)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.kotlinx.serialization.core)
    // reading statistics
    implementation(project(":feature:stats"))
}
