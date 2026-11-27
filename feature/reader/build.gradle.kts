plugins {
    alias(libs.plugins.zenread.android.feature)
}

android {
    namespace = "com.zenread.feature.reader"
}

dependencies {
    implementation(project(":reader:engine"))
    implementation(project(":data:library"))
    implementation(project(":core:datastore"))
    implementation(project(":data:tts"))
    implementation(project(":feature:fonts"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(project(":data:translate"))
    // phase 5 (AI)
    implementation(project(":data:ai"))
    implementation(libs.markdown.renderer.m3)
    // reading statistics
    implementation(project(":data:stats"))
}
