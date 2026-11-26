plugins {
    alias(libs.plugins.zenread.android.feature)
}

android {
    namespace = "com.zenread.feature.settings"
}

dependencies {
    implementation(project(":data:sync"))
}
