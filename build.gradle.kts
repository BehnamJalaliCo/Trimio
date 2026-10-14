plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.detekt)
}

/**
 * Static analysis over every module's Kotlin sources. Existing findings are recorded in the
 * baseline; CI fails only on new ones. Regenerate with `./gradlew detektBaseline` after a review.
 */
detekt {
    buildUponDefaultConfig = true
    parallel = true
    config.setFrom(files("config/detekt/detekt.yml"))
    baseline = file("config/detekt/baseline.xml")
    source.setFrom(
        fileTree(rootDir) {
            include("**/src/**/kotlin/**/*.kt")
            exclude("**/build/**", "native/**", "build-logic/**")
        },
    )
}

/**
 * Test JVMs that hold real models, media or offscreen Skia surfaces (2–4 GB of heap each, plus native
 * memory) take turns: several of them at once beside the Gradle and Kotlin daemons exhaust a 16 GB CI
 * runner, which then shuts down mid-run. Every other test task still runs in parallel.
 */
abstract class HeavyTests : BuildService<BuildServiceParameters.None>

val heavyTests = gradle.sharedServices.registerIfAbsent("heavyTests", HeavyTests::class) { maxParallelUsages.set(1) }
val heavyTestProjects = setOf(
    ":engine:autopilot", ":engine:production", ":engine:motion", ":engine:retrieval", ":engine:illustration",
    ":engine:perception", ":engine:render", ":engine:styles", ":desktopApp",
)
subprojects {
    if (path in heavyTestProjects) tasks.withType<Test>().configureEach { usesService(heavyTests) }
}

// The Kotlin/Wasm toolchains (Node.js, Yarn, Binaryen) resolve through the repositories declared
// in settings.gradle.kts; stop the plugins from adding their own project-level ones.
plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsPlugin> {
    the<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec>().downloadBaseUrl.set(null as String?)
}
plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnPlugin> {
    the<org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnRootEnvSpec>().downloadBaseUrl.set(null as String?)
}
allprojects {
    plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenPlugin> {
        the<org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenEnvSpec>().downloadBaseUrl.set(null as String?)
    }
}
