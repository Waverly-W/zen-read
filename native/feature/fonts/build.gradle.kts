plugins {
    alias(libs.plugins.zenread.android.feature)
}

android {
    namespace = "com.zenread.feature.fonts"
}

// Font picker UI embedded by the reader's typography sheet and the Reading settings page.
// It has no navigation entry of its own.
dependencies {
    implementation(project(":data:fonts"))
    implementation(project(":core:datastore"))
    implementation(libs.androidx.activity.compose)
}
