import com.android.build.api.dsl.LibraryExtension
import com.zenread.buildlogic.lib
import com.zenread.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType

/**
 * Robolectric for JVM unit tests (Room migrations, Room-backed logic).
 *
 * Robolectric normally downloads its Android framework jar at test time with the test JVM's
 * own TLS stack, which fails on machines whose JDK has no CA store. Gradle resolves the jar
 * instead and Robolectric runs offline from it. Tests must use `@Config(sdk = [35])`, the
 * SDK of the `robolectric-android-all` jar in the version catalog.
 */
class RobolectricConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val androidAll = configurations.create("robolectricAndroidAll") {
            isCanBeConsumed = false
            isTransitive = false
        }
        dependencies {
            add(androidAll.name, libs.lib("robolectric-android-all"))
            add("testImplementation", libs.lib("robolectric"))
            add("testImplementation", libs.lib("androidx-test-core"))
        }
        val jarDir = layout.buildDirectory.dir("robolectric-jars")
        val syncJars = tasks.register<Sync>("syncRobolectricJars") {
            from(androidAll)
            into(jarDir)
        }
        tasks.withType<Test>().configureEach {
            dependsOn(syncJars)
            systemProperty("robolectric.offline", "true")
            systemProperty("robolectric.dependency.dir", jarDir.get().asFile.absolutePath)
        }
        extensions.configure<LibraryExtension> {
            testOptions.unitTests.isIncludeAndroidResources = true
        }
    }
}
