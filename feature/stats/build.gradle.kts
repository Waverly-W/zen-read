plugins {
    alias(libs.plugins.zenread.android.feature)
}

android {
    namespace = "com.zenread.feature.stats"
}

dependencies {
    implementation(project(":data:stats"))
    implementation(libs.coil.compose)
}
