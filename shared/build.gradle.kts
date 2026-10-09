plugins {
    alias(libs.plugins.trimio.kmp.compose)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            api(projects.core.pipeline)
            api(projects.core.designsystem)
            api(projects.feature.stream)
            api(libs.koin.core)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
        }
    }
}
