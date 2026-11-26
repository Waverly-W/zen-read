plugins {
    alias(libs.plugins.zenread.android.library)
    alias(libs.plugins.zenread.hilt)
}

android {
    namespace = "com.zenread.core.network"
}

dependencies {
    api(libs.okhttp)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
}
