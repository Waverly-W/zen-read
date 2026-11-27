plugins {
    alias(libs.plugins.zenread.android.library)
    alias(libs.plugins.zenread.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.zenread.reader.engine"
    androidResources {
        // pdf.js ships pre-compressed cmaps; keep every vendored file byte-exact.
        noCompress += listOf("bcmap", "pfb", "wasm")
    }
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:common"))
    implementation(libs.androidx.webkit)
    api(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
}
