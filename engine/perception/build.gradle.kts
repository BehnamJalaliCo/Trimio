plugins {
    alias(libs.plugins.trimio.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * What Trimio sees and hears in the footage beyond words: faces and free space for text, shot
 * cuts, music beats, punctuation, exact word times, who speaks when, text already in the picture,
 * a learned look score and spoken emphasis. Every feature has a model-free fallback, so a missing
 * add-on only lowers quality, never breaks an edit.
 */
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            api(projects.engine.onnx)
            api(projects.engine.models)
            api(projects.engine.audio)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

tasks.named<Test>("jvmTest") {
    maxHeapSize = "2g"
    // Real models and media (never committed): -Ptrimio.mediaModels=<dir> -Ptrimio.mediaTest=<dir>.
    providers.gradleProperty("trimio.mediaModels").orNull?.let { systemProperty("trimio.media.models", it) }
    providers.gradleProperty("trimio.mediaTest").orNull?.let { systemProperty("trimio.media.test", it) }
    providers.gradleProperty("trimio.aestheticData").orNull?.let { systemProperty("trimio.aesthetic.data", it) }
}
