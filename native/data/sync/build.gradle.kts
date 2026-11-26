plugins {
    alias(libs.plugins.zenread.android.library)
    alias(libs.plugins.zenread.hilt)
    alias(libs.plugins.zenread.robolectric)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.zenread.data.sync"
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:network"))
    implementation(project(":data:library"))

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.startup.runtime)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.okhttp.mockwebserver3)
    testImplementation(libs.kotlinx.coroutines.test)
}
