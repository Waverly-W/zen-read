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
    implementation(project(":data:sync"))
    implementation(project(":core:datastore"))
    implementation(project(":feature:fonts"))
    // appearance: per-app language
    implementation(libs.androidx.appcompat)
}
