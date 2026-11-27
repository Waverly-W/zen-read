import com.android.build.api.dsl.LibraryExtension
import com.zenread.buildlogic.ZenReadSdk
import com.zenread.buildlogic.configureKotlin
import com.zenread.buildlogic.lib
import com.zenread.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")

        extensions.configure<LibraryExtension> {
            compileSdk {
                version = release(ZenReadSdk.COMPILE) { minorApiLevel = ZenReadSdk.COMPILE_MINOR }
            }
            defaultConfig {
                minSdk = ZenReadSdk.MIN
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
            compileOptions {
                sourceCompatibility = ZenReadSdk.JAVA
                targetCompatibility = ZenReadSdk.JAVA
            }
        }
        configureKotlin()

        dependencies {
            add("testImplementation", libs.lib("junit"))
        }
    }
}
