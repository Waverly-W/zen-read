plugins {
    alias(libs.plugins.zenread.android.feature)
}

android {
    namespace = "com.zenread.feature.library"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":data:library"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.coil.compose)
}
