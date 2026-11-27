plugins {
    alias(libs.plugins.zenread.android.library)
    alias(libs.plugins.zenread.android.compose)
}

android {
    namespace = "com.zenread.core.designsystem"
}

dependencies {
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.material.icons.extended)
    // Appearance: preset accents (ThemeAccent) for the fallback color schemes.
    api(project(":core:model"))
}
