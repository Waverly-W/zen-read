plugins {
    alias(libs.plugins.zenread.android.library)
    alias(libs.plugins.zenread.hilt)
}

android {
    namespace = "com.zenread.core.database"
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    api(project(":core:model"))
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
}
