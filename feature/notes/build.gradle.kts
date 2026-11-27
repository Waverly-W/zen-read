plugins {
    alias(libs.plugins.zenread.android.feature)
}

android {
    namespace = "com.zenread.feature.notes"
}

dependencies {
    implementation(project(":data:library"))
    implementation(libs.androidx.activity.compose)
}
