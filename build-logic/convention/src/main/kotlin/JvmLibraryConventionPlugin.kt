import com.zenread.buildlogic.ZenReadSdk
import com.zenread.buildlogic.configureKotlin
import com.zenread.buildlogic.lib
import com.zenread.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/** Pure Kotlin module with no Android dependencies (models, pure logic). */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")

        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = ZenReadSdk.JAVA
            targetCompatibility = ZenReadSdk.JAVA
        }
        configureKotlin()

        dependencies {
            add("testImplementation", libs.lib("junit"))
        }
    }
}
