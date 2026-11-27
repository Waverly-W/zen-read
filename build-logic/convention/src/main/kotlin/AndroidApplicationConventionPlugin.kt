import com.android.build.api.dsl.ApplicationExtension
import com.zenread.buildlogic.ZenReadSdk
import com.zenread.buildlogic.configureKotlin
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")

        extensions.configure<ApplicationExtension> {
            compileSdk {
                version = release(ZenReadSdk.COMPILE) { minorApiLevel = ZenReadSdk.COMPILE_MINOR }
            }
            defaultConfig {
                minSdk = ZenReadSdk.MIN
                targetSdk = ZenReadSdk.TARGET
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
            compileOptions {
                sourceCompatibility = ZenReadSdk.JAVA
                targetCompatibility = ZenReadSdk.JAVA
            }
        }
        configureKotlin()
    }
}
