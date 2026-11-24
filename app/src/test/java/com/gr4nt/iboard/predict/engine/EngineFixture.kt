package com.gr4nt.iboard.predict.engine

import java.io.File

/** Loads the real shipped model files from the app's assets, exactly as the keyboard does. */
object EngineFixture {
    val assets: File = listOf("src/main/assets/prediction", "app/src/main/assets/prediction").map(::File).first { it.exists() }

    fun engine(nano: NanoBooster? = null, personal: PersonalStore = PersonalStore(MemoryPersistence(), "en")): PredictionEngine =
        PredictionEngine.fromFiles(assets, "en", personal, nano)

    /** Words the engine puts first for [head] (what is typed so far is [typed]). */
    fun top(e: PredictionEngine, head: String, typed: String = "", n: Int = 3, useLm: Boolean = true, nano: List<String> = emptyList()): List<String> =
        e.rank(PredictionEngine.Request(head, true, typed, useLm = useLm, nano = nano, limit = n)).map { it.text.lowercase() }
}
