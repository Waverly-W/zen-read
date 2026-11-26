plugins {
    alias(libs.plugins.zenread.android.feature)
}

android {
    namespace = "com.zenread.feature.settings"
}

dependencies {
    implementation(project(":data:translate"))
    implementation(project(":data:tts"))
    // phase 5 (AI)
    implementation(project(":data:ai"))
    implementation(libs.androidx.activity.compose)
}
