plugins {
    alias(libs.plugins.zenread.android.feature)
}

android {
    namespace = "com.zenread.feature.reader"
}

dependencies {
    implementation(project(":reader:engine"))
    implementation(project(":data:library"))
    implementation(project(":core:datastore"))
    implementation(project(":feature:fonts"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
}
