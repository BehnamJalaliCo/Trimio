plugins {
    alias(libs.plugins.trimio.kmp.compose)
}

kotlin {
    sourceSets {
        // The production screens (live build, result, failure) are pure functions of their models.
        commonMain.dependencies {
            implementation(projects.core.model)
            implementation(projects.core.designsystem)
            // The system back leaves the player's full screen (already part of Compose UI at runtime).
            implementation(libs.compose.ui.backhandler)
        }
    }
}

kotlin {
    sourceSets {
        jvmTest.dependencies {
            // Desktop Skia lets screenshot tests render the real screen off-screen.
            implementation(compose.desktop.currentOs)
        }
    }
}
