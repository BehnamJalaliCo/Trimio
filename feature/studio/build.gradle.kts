plugins {
    alias(libs.plugins.trimio.kmp.compose)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.model)
            implementation(projects.core.data)
            implementation(projects.core.designsystem)
            implementation(projects.engine.render)
            implementation(projects.engine.models)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.androidx.lifecycle.runtime.compose)
        }
        jvmTest.dependencies { implementation(compose.desktop.currentOs) }
    }
}
