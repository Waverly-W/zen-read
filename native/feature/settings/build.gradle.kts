plugins {
    alias(libs.plugins.zenread.android.feature)
}

android {
    namespace = "com.zenread.feature.settings"
}

dependencies {
    // phase 5 (AI)
    implementation(project(":data:ai"))
    implementation(libs.androidx.activity.compose)
}
