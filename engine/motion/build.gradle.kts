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
            // Sound cues of the sample pieces are voiced with the procedural effects library.
            implementation(projects.engine.assets)
        }
    }
}

// Review knobs for the render tests: -Pmotion.frames=1.2,4.5 dumps stills; -Pmotion.video=false skips MP4s.
tasks.withType<Test>().configureEach {
    providers.gradleProperty("motion.frames").orNull?.let { systemProperty("motion.frames", it) }
    providers.gradleProperty("motion.video").orNull?.let { systemProperty("motion.video", it) }
    maxHeapSize = "3g"
}
