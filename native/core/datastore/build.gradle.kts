plugins {
    alias(libs.plugins.zenread.android.library)
    alias(libs.plugins.zenread.hilt)
}

android {
    namespace = "com.zenread.core.datastore"
}

dependencies {
    api(project(":core:model"))
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.core)
}
