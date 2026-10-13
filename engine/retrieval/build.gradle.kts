plugins {
    alias(libs.plugins.trimio.kmp.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Retrieval: what fits each sentence, found by meaning. Hybrid search (BM25 over normalised
 * Persian/English terms and dense vectors from the on-device embedder, fused by reciprocal rank),
 * a reranker over the shortlist, and a relevance floor below which nothing is offered. Corpora:
 * the picture catalogue, the explainer primitives and card families, music and sound cues, the
 * golden exemplars, a Persian domain glossary, and the creator's own memory. Vector packs are
 * built offline with the same embedder (tools/retrieval) and ship in composeResources.
 */
compose.resources {
    packageOfResClass = "io.trimio.engine.retrieval.resources"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            api(projects.engine.llm)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

// -Pretrieval.encoders=<dir> -Pretrieval.native=<dir>: the evaluation and the pack builder run the real models.
tasks.withType<Test>().configureEach {
    listOf("retrieval.encoders", "retrieval.native", "retrieval.out", "retrieval.only").forEach { key ->
        providers.gradleProperty(key).orNull?.let { systemProperty(key, it) }
    }
    providers.gradleProperty("retrieval.native").orNull?.let { systemProperty("trimio.native.dir", it) }
    maxHeapSize = "3g"
}

kotlin {
    sourceSets {
        // The evaluation compares with the matcher the app used before (engine/assets ArtCatalogue).
        jvmTest.dependencies { implementation(projects.engine.assets) }
    }
}
