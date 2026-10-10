plugins {
    alias(libs.plugins.trimio.kmp.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * The motion language: a scene graph with keyframed properties (engine), a library of
 * professional motion recipes, the compact score the director writes, and the compiler that turns
 * a score into a finished composition (timing, layout, readability and polish rules).
 */
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            api(projects.core.brand)
            implementation(libs.kotlinx.serialization.json)
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
        }
    }
}
