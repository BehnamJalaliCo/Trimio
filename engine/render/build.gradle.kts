plugins {
    alias(libs.plugins.trimio.kmp.compose)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            api(projects.core.pipeline)
            api(projects.engine.audio)
            api(projects.engine.media)
            implementation(projects.core.designsystem)
        }
        androidMain.dependencies {
            implementation(libs.media3.transformer)
            implementation(libs.media3.effect)
            implementation(libs.media3.common)
        }
        jvmTest.dependencies {
            // Native Skia so tests rasterise real frames (golden images, MP4 previews).
            implementation(compose.desktop.currentOs)
        }
    }
}
