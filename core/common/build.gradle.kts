plugins {
    alias(libs.plugins.zenread.android.library)
    alias(libs.plugins.zenread.hilt)
}

android {
    namespace = "com.zenread.core.common"
}

dependencies {
    api(libs.kotlinx.coroutines.android)
}
