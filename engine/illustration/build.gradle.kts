plugins {
    alias(libs.plugins.trimio.kmp.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Illustration: one hero picture per scene of an audio-only explainer film, all in the film's one art
 * style (pencil sketch with a single accent, ink, flat...). Sources: the bundled composable library
 * (Open Peeps people with poses and expressions, CC0; Fluent Emoji objects and places, MIT), the
 * creator's own photos, and — as a downloadable add-on — an on-device image generator; a procedural
 * styler turns every one of them into the same look. Pure Kotlin rasterising and styling, so a picture
 * is the same on every device for the same seed.
 */
compose.resources {
    packageOfResClass = "io.trimio.engine.illustration.resources"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

// -Pillustration.sheets=<dir>: the contact-sheet tests write their PNGs there. -Pillustration.generated=<dir>: generator
// output to style for review; -Pillustration.templates=<dir>: write the library's sketches (GeneratorSheetTest).
tasks.withType<Test>().configureEach {
    listOf("illustration.sheets", "illustration.generated", "illustration.templates").forEach { key ->
        providers.gradleProperty(key).orNull?.let { systemProperty(key, it) }
    }
    systemProperty("illustration.root", rootProject.layout.projectDirectory.asFile.absolutePath)
    maxHeapSize = "2g"
}
