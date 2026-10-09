import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Base setup for every shared Trimio module.
 *
 * Targets:
 *  - android  : the product we ship today (Android 13+, API 33)
 *  - jvm      : fast host-side unit tests and a desktop dev harness for style previews
 *  - ios*     : declared now so common code stays iOS-safe (compiled on macOS only)
 *  - wasmJs   : future web version
 *
 * The namespace is derived from the Gradle path, e.g. `:core:model` -> `io.trimio.core.model`.
 */
class KmpLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        pluginManager.apply("com.android.kotlin.multiplatform.library")

        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        fun version(alias: String) = libs.findVersion(alias).get().requiredVersion.toInt()

        extensions.configure<KotlinMultiplatformExtension> {
            jvmToolchain(21)

            (this as ExtensionAware).extensions.configure<KotlinMultiplatformAndroidLibraryTarget>("android") {
                namespace = "io.trimio" + path.replace(':', '.').replace('-', '_')
                compileSdk = version("android-compileSdk")
                minSdk = version("android-minSdk")
                compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
            }

            jvm()

            iosArm64()
            iosSimulatorArm64()

            @OptIn(ExperimentalWasmDsl::class)
            wasmJs { browser() }

            // Platforms that render through Skia/Skiko (everything except Android) share a source set,
            // so runtime shaders and text shaping have one implementation outside Android.
            @OptIn(ExperimentalKotlinGradlePluginApi::class)
            applyDefaultHierarchyTemplate {
                common {
                    group("skiko") {
                        withJvm()
                        group("ios") { withIos() }
                        withWasmJs()
                    }
                }
            }

            compilerOptions {
                freeCompilerArgs.add("-Xexpect-actual-classes")
            }

            sourceSets.getByName("commonMain").dependencies {
                implementation(libs.findLibrary("kotlinx-coroutines-core").get())
            }
            sourceSets.getByName("commonTest").dependencies {
                implementation(kotlin("test"))
                implementation(libs.findLibrary("kotlinx-coroutines-test").get())
                implementation(libs.findLibrary("turbine").get())
            }
        }
    }
}
