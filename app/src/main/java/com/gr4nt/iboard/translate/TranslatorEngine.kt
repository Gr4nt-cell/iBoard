package com.gr4nt.iboard.translate

import android.content.Context
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device translation (Google ML Kit). Each language pack (~30 MB) is downloaded once; after
 * that, translation runs entirely on the phone and works offline. Text is never sent to a server.
 */
class TranslatorEngine(private val context: Context) {
    companion object {
        const val DETECT = "auto"
        const val PREF_FROM = "translate_from"
        const val PREF_TO = "translate_to"

        /** Every language ML Kit can translate, sorted by its English name. */
        val languages: List<String> by lazy {
            TranslateLanguage.getAllLanguages().sortedBy { name(it) }
        }

        fun name(code: String): String =
            if (code == DETECT) "Detect language"
            else Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).replaceFirstChar { it.uppercase() }

        fun short(code: String): String = if (code == DETECT) "Detect" else name(code)
    }

    private val translators = HashMap<String, Translator>()
    private val identifier by lazy { LanguageIdentification.getClient() }
    private val ready = HashSet<String>()

    private fun key(from: String, to: String) = "$from>$to"

    private fun translator(from: String, to: String): Translator =
        translators.getOrPut(key(from, to)) {
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build())
        }

    /** True when both language packs are already on the phone. */
    suspend fun isDownloaded(from: String, to: String): Boolean {
        if (ready.contains(key(from, to))) return true
        val mgr = RemoteModelManager.getInstance()
        val models = await(mgr.getDownloadedModels(TranslateRemoteModel::class.java)).map { it.language }.toSet()
        // English is built in and needs no pack.
        val ok = (from == TranslateLanguage.ENGLISH || from in models) && (to == TranslateLanguage.ENGLISH || to in models)
        if (ok) ready.add(key(from, to))
        return ok
    }

    suspend fun download(from: String, to: String) {
        await(translator(from, to).downloadModelIfNeeded(DownloadConditions.Builder().build()))
        ready.add(key(from, to))
    }

    /** Best guess at the source language, or null when unsure. */
    suspend fun detect(text: String): String? {
        val tag = await(identifier.identifyLanguage(text))
        if (tag == "und") return null
        return TranslateLanguage.fromLanguageTag(tag)
    }

    suspend fun translate(text: String, from: String, to: String): String {
        if (from == to) return text
        return await(translator(from, to).translate(text))
    }

    fun close() {
        translators.values.forEach { it.close() }
        translators.clear()
        runCatching { identifier.close() }
    }

    private suspend fun <T> await(task: com.google.android.gms.tasks.Task<T>): T =
        suspendCancellableCoroutine { cont ->
            task.addOnSuccessListener { cont.resume(it) }
            task.addOnFailureListener { cont.resumeWithException(it) }
        }
}
