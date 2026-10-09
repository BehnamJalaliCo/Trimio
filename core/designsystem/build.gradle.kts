plugins {
    alias(libs.plugins.trimio.kmp.compose)
}

compose.resources {
    publicResClass = true
    packageOfResClass = "io.trimio.core.designsystem.resources"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            api(libs.haze)
            api(libs.haze.blur)
        }
    }
}

kotlin {
    sourceSets {
        jvmTest.dependencies {
            // Native Skia for the host, so shader tests can compile and rasterise SkSL.
            implementation(compose.desktop.currentOs)
        }
    }
}
