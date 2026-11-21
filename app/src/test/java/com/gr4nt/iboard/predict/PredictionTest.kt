package com.gr4nt.iboard.predict

import com.gr4nt.iboard.*
import com.gr4nt.iboard.ai.OnDeviceAiEngine
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class PredictionTest {
    @get:Rule val folder = TemporaryFolder()

    private fun suggester(lang: String = "en"): Suggester {
        val words = listOf("I", "am", "is", "are", "has", "have", "was", "were", "do", "does", "did", "go", "goes", "went", "gone", "going", "done", "doing", "be", "been", "seen", "saw", "see", "happy", "ready", "sure", "want", "wants", "need", "needs", "like", "apple", "university", "hour", "unimaginable", "the", "a", "you").sortedBy { Dictionary.normalize(it) }
        val lookup = HashMap<String, Float>()
        words.forEach { lookup[it.lowercase()] = 4f }
        val model = ContextModel(
            mapOf("i" to listOf("is" to .9f, "am" to .1f), "am" to listOf("go" to .9f, "going" to .1f)),
            mapOf("i\tam" to listOf("going" to .7f, "ready" to .3f)),
        )
        val dict = Dictionary(lang, words.map { Dictionary.normalize(it) }.toTypedArray(), words.toTypedArray(), FloatArray(words.size) { 4f }, lookup,
            listOf("the", "a", "you"), emptyMap(), model)
        return Suggester(dict, UserModel(folder.newFolder(), lang), Suggester.neighborsFor(Layouts.letterRows.getValue("en")))
    }

    @Test fun agreementBeatsFrequentLearnedMistakes() {
        val s = suggester()
        repeat(100) { s.user.learn("is", "I") }
        assertEquals("am", s.nextWords("I", false).first().text)
        assertFalse(s.nextWords("I", false).take(3).any { it.text in setOf("is", "are", "has") })
        assertTrue(s.nextWords("she", false).take(3).any { it.text == "is" })
        assertFalse(s.nextWords("they", false).take(3).any { it.text in setOf("is", "has", "was") })
    }

    @Test fun contextAlsoRanksPrefixCompletions() {
        val s = suggester()
        val result = s.forWord("go", "am", false, false, "I")
        assertEquals("going", result.items.first().text)
        assertNull(result.autoCorrect) // valid words remain the user's choice
        assertTrue(result.items.any { it.text == "go" && it.kind == Suggestion.Kind.LITERAL })
    }

    @Test fun grammarDoesNotLeakIntoOtherLanguagesOrAutoRewrite() {
        val s = suggester("fr")
        assertEquals("is", s.nextWords("i", false).first().text)
        assertNull(s.forWord("have", "she", true).autoCorrect)
        assertNull(suggester().forWord("have", "she", true).autoCorrect)
    }

    @Test fun auxiliaryAndParticipleForms() {
        assertTrue(EnglishGrammar.adjustment("can", "she", "go") > EnglishGrammar.adjustment("can", "she", "goes"))
        assertTrue(EnglishGrammar.adjustment("did", "I", "see") > EnglishGrammar.adjustment("did", "I", "saw"))
        assertTrue(EnglishGrammar.adjustment("have", "I", "seen") > EnglishGrammar.adjustment("have", "I", "saw"))
        assertTrue(EnglishGrammar.adjustment("is", "she", "gone") > EnglishGrammar.adjustment("is", "she", "went"))
    }

    @Test fun articlesRespectCommonSoundExceptions() {
        assertTrue(EnglishGrammar.adjustment("an", null, "apple") > EnglishGrammar.adjustment("a", null, "apple"))
        assertTrue(EnglishGrammar.adjustment("a", null, "university") > EnglishGrammar.adjustment("an", null, "university"))
        assertTrue(EnglishGrammar.adjustment("an", null, "hour") > EnglishGrammar.adjustment("a", null, "hour"))
        assertTrue(EnglishGrammar.adjustment("an", null, "unimaginable") > EnglishGrammar.adjustment("a", null, "unimaginable"))
    }

    @Test fun sentenceContextNeverCrossesNewlineOrPunctuation() {
        assertEquals(PredictionContext("am", "I", false), PredictionContext.from("Old sentence. I am "))
        assertEquals(PredictionContext(null, null, true), PredictionContext.from("Hello\n"))
        assertEquals(PredictionContext(null, null, true), PredictionContext.from("مرحبا؟ "))
        assertEquals(PredictionContext("don't", "I", false), PredictionContext.from("I don’t "))
        assertEquals(PredictionContext("You", null, false), PredictionContext.from("I was. You "))
    }

    @Test fun numpadKeepsDigitsAndDirectAtAndHashInEveryLanguage() {
        for (lang in Layouts.letterRows.keys) for (row in listOf(false, true)) {
            val keys = Layouts.build(lang, Page.NUMBER_PAD, row, FieldKind.TEXT, true).rows.flatMap { it.keys }
            val labels = keys.filter { it.type == KeyType.CHAR }.map { it.label }.toSet()
            assertTrue(labels.containsAll(('0'..'9').map { it.toString() } + listOf("@", "#")))
            assertTrue(keys.first { it.label == "@" }.longPress.contains("/"))
            assertTrue(keys.first { it.label == "#" }.longPress.contains("*"))
            assertTrue(keys.any { it.type == KeyType.BACKSPACE })
            assertTrue(keys.any { it.type == KeyType.RETURN })
        }
    }

    @Test fun secureEditorRestartsKeepCurrentKeyboardPageAndAnimation() {
        val field = EditorSessionKey("example.app", 7, "password", 129, 6)
        assertTrue(InputSessionPolicy.preserve(true, field, field.copy(), true))
        assertFalse(InputSessionPolicy.preserve(false, field, field.copy(), true))
        assertFalse(InputSessionPolicy.preserve(true, field, field.copy(fieldId = 8), true))
        assertFalse(InputSessionPolicy.preserve(true, field, field, false))
        // Password visibility toggles update input policy, without treating the same field as new focus.
        assertTrue(field.sameField(field.copy(inputType = 145)))
        assertFalse(InputSessionPolicy.preserve(true, field, field.copy(inputType = 145), true))
    }

    @Test fun floatingPositionIsBoundedAndSurvivesResize() {
        assertEquals(12, FloatingBounds.position(-1f, 12, 300))
        assertEquals(300, FloatingBounds.position(2f, 12, 300))
        assertEquals(12, FloatingBounds.position(.5f, 12, 4))
        assertEquals(0f, FloatingBounds.fraction(-20f, 12, 300), 0f)
        assertEquals(1f, FloatingBounds.fraction(400f, 12, 300), 0f)
        val fraction = FloatingBounds.fraction(156f, 12, 300)
        assertEquals(256, FloatingBounds.position(fraction, 12, 500))
    }

    @Test fun generatedSuggestionsArePlainUniqueAndLimited() {
        assertEquals(
            listOf("thanks", "I can help"),
            OnDeviceAiEngine.parsePredictions("1. thanks\n- I can help\n\"Thanks\"\nThis line is far too long to fit in a keyboard suggestion and should be ignored"),
        )
    }
}
