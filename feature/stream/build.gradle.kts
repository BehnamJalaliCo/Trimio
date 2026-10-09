plugins {
    alias(libs.plugins.trimio.kmp.compose)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.model)
            implementation(projects.core.pipeline)
            implementation(projects.core.designsystem)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.androidx.lifecycle.runtime.compose)
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
