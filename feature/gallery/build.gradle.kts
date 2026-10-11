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
            implementation(projects.engine.motion)
            implementation(projects.engine.production)
            // The style cards' stills (shown until a card's live sample is ready).
            implementation(projects.feature.create)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.androidx.lifecycle.runtime.compose)
        }
        jvmTest.dependencies { implementation(compose.desktop.currentOs) }
    }
}
