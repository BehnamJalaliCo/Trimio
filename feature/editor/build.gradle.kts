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
            implementation(projects.engine.styles)
            implementation(projects.engine.director)
            implementation(projects.engine.llm)
            implementation(projects.engine.media)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.androidx.lifecycle.runtime.compose)
        }
        androidMain.dependencies {
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.ui)
        }
        jvmTest.dependencies { implementation(compose.desktop.currentOs) }
    }
}
