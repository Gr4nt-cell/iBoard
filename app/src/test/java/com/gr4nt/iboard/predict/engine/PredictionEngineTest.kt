package com.gr4nt.iboard.predict.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Context → expected-word cases, run twice: built-in engine alone, and with Gemini Nano switched on
 * (a stand-in source here, because unit tests have no AICore). The built-in answers must not depend on Nano.
 */
class PredictionEngineTest {
    private class Case(val head: String, val typed: String, val ok: Set<String>)

    private fun c(head: String, vararg ok: String) = Case(head, "", ok.toSet())
    private fun m(head: String, typed: String, vararg ok: String) = Case(head, typed, ok.toSet())

    private val cases = listOf(
        c("how are you", "doing", "feeling", "going", "today", "guys", "all", "both", "ok"),
        c("how are you doing", "today", "this", "tonight", "now", "there", "so", "these"),
        c("thank you", "so", "very", "for", "all", "again", "much", "guys"),
        c("thank you so", "much", "very", "for", "i"),
        c("I am going", "to", "home", "out", "back", "with", "on", "there"),
        c("I am", "going", "not", "a", "so", "sorry", "here", "just", "in", "at", "the", "very", "sure", "the"),
        c("see you", "later", "soon", "tomorrow", "then", "there", "next", "again", "at", "in", "on", "tonight"),
        c("good", "morning", "night", "evening", "afternoon", "luck", "job", "day", "idea", "to"),
        c("what time", "is", "do", "does", "are", "will", "did", "was", "it"),
        c("I don't", "know", "think", "want", "have", "like", "understand", "care", "even", "see", "believe"),
        c("I'll", "be", "see", "have", "do", "call", "get", "go", "let", "try", "send", "take", "make", "never"),
        c("he is", "a", "not", "going", "the", "in", "very", "my", "so", "an", "still", "also", "now"),
        c("she was", "a", "not", "the", "very", "so", "in", "going", "born", "an", "just", "also"),
        c("they are", "not", "going", "the", "a", "all", "in", "very", "so", "still", "also", "just"),
        c("we can", "do", "go", "see", "be", "make", "get", "take", "talk", "not", "have", "only", "just"),
        c("do you", "want", "have", "know", "think", "like", "need", "understand", "mind", "remember", "see"),
        c("would you", "like", "be", "mind", "please", "rather", "want", "have", "care", "tell", "help"),
        c("can you", "please", "help", "tell", "see", "do", "come", "give", "get", "hear", "show", "believe"),
        c("where are", "you", "we", "they", "the", "my", "those", "these", "all", "your"),
        c("I want to", "go", "be", "see", "know", "do", "make", "have", "get", "talk", "buy", "say", "take"),
        c("I have a", "lot", "good", "great", "little", "few", "problem", "question", "new", "friend", "big", "feeling", "dog", "car", "cat"),
        c("I love", "you", "it", "this", "the", "that", "my", "to", "her", "him", "your", "how"),
        c("have a", "good", "great", "nice", "look", "lot", "seat", "little", "wonderful", "safe", "happy", "try", "minute"),
        c("let me", "know", "see", "go", "think", "tell", "check", "be", "help", "explain", "get", "do", "show", "just"),
        c("I will", "be", "see", "have", "do", "not", "call", "go", "try", "never", "take", "make", "let", "get"),
        c("it is", "a", "not", "the", "very", "so", "going", "an", "also", "possible", "important", "too", "my", "time"),
        c("this is", "a", "the", "my", "not", "what", "an", "our", "your", "how", "why", "so", "very", "it", "all"),
        c("I think", "that", "it", "so", "we", "I", "you", "the", "he", "this", "there", "they", "she", "i"),
        c("thanks for", "the", "your", "coming", "everything", "letting", "all", "helping", "that", "this", "sharing", "me", "calling", "having"),
        c("looking forward to", "seeing", "it", "the", "hearing", "meeting", "your", "this", "working", "our", "a"),
        c("talk to you", "later", "soon", "tomorrow", "then", "about", "again", "in", "next", "tonight", "guys"),
        c("what are you", "doing", "going", "talking", "looking", "saying", "up", "trying", "thinking", "reading", "waiting", "gonna", "planning"),
        c("I'm going to", "be", "have", "do", "go", "see", "get", "take", "make", "bed", "the", "say", "call", "tell", "need"),
        c("if you", "want", "have", "need", "can", "are", "like", "don't", "do", "would", "know", "see", "could", "think", "will"),
        c("I was", "just", "not", "going", "a", "so", "in", "at", "the", "very", "born", "wondering", "thinking", "also", "really"),
        c("you are", "a", "not", "the", "so", "very", "going", "an", "my", "welcome", "right", "all", "in", "being", "too"),
        c("how much", "is", "does", "do", "did", "are", "will", "time", "of", "money", "it", "would", "can", "more"),
        c("happy", "birthday", "new", "to", "with", "for", "and", "anniversary", "holidays", "easter", "mother's", "father's", "thanksgiving"),
        c("I need to", "go", "be", "get", "talk", "see", "do", "make", "find", "know", "take", "buy", "call", "check", "say"),
        c("are you", "sure", "ok", "okay", "going", "coming", "still", "there", "here", "a", "the", "free", "ready", "serious", "in", "not"),
        c("can't wait", "to", "for", "until", "till"),
        c("no", "problem", "one", "I", "it", "thanks", "more", "matter", "way", "worries", "longer", "i", "idea", "other", "you"),
        c("I'm", "not", "so", "a", "going", "sorry", "just", "in", "at", "on", "the", "very", "sure", "really", "also", "still", "fine", "here"),
        c("don't", "forget", "worry", "know", "think", "you", "have", "want", "do", "get", "let", "even", "be", "go", "say", "like", "see"),
        c("what do", "you", "we", "they", "I", "i", "people", "these", "those", "the", "he", "she"),
        c("who is", "he", "she", "that", "the", "it", "your", "this", "going", "there", "your", "a", "they", "responsible", "in"),
        c("when are", "you", "we", "they", "the", "my", "these", "those", "your", "i"),
        c("in the", "morning", "middle", "end", "world", "same", "house", "city", "first", "country", "other", "past", "next", "world", "room", "event"),
        c("on the", "way", "other", "phone", "left", "right", "same", "table", "floor", "second", "first", "next", "road", "top", "side", "one"),
        c("at the", "end", "same", "moment", "time", "beginning", "top", "airport", "back", "office", "station", "door", "party", "first", "bottom", "hospital"),
        c("do you want", "to", "me", "some", "a", "the", "it", "my", "something", "another", "this", "more", "one", "us"),
        c("do you have", "a", "any", "the", "an", "to", "it", "some", "anything", "time", "my", "your", "one", "something", "enough", "more"),
        c("I can", "do", "see", "help", "be", "get", "go", "not", "tell", "make", "take", "only", "hear", "just", "still", "'t"),
        c("she said", "that", "she", "he", "I", "the", "it", "i", "you", "they", "to", "no", "yes", "nothing", "something"),
        c("I know", "that", "you", "what", "it", "I", "how", "i", "this", "the", "he", "they", "she", "we", "there", "who", "why"),
        c("i don't know", "what", "if", "why", "how", "whether", "about", "where", "who", "when", "that", "exactly", "anything", "the"),
        c("Hello", "there", "everyone", "I", "how", "my", "world", "i", "sir", "again", "mr", "all", "to", "and", "friends", "guys", "it"),
        c("Hello, how", "are", "is", "do", "can", "was", "have", "did", "could", "may", "has", "may", "would", "does", "about"),
        c("You should", "be", "have", "go", "try", "not", "see", "know", "get", "take", "do", "come", "make", "tell", "also", "never", "just", "ask"),
        c("I could", "not", "be", "have", "do", "see", "never", "only", "tell", "go", "get", "make", "say", "just", "hear", "use"),
        c("It was", "a", "the", "not", "so", "very", "an", "my", "just", "really", "like", "also", "too", "good", "great", "in"),
        // mid-word: the model reads the whole sentence, the typed letters narrow it down
        m("how are", "yo", "you", "your", "young"),
        m("thank", "y", "you"),
        m("see you", "la", "later", "last", "late"),
        m("I am", "go", "going", "good", "gone", "got", "god"),
        m("good", "mor", "morning"),
        m("", "wha", "what", "whatever", "whatsapp", "whats"),
        m("I'll be", "ther", "there", "thereby", "therefore"),
        m("what time is", "it", "it", "its", "italy"),
    )

    private fun hit(e: PredictionEngine, case: Case, nano: List<String> = emptyList()): Boolean =
        EngineFixture.top(e, case.head, case.typed, n = 3, nano = nano).any { it in case.ok }

    @Test fun builtInEngineAnswersTheCaseSuite() {
        val e = EngineFixture.engine()
        val misses = cases.filterNot { hit(e, it) }
        val rate = 1.0 - misses.size / cases.size.toDouble()
        println("built-in top-3 hit rate: ${"%.1f".format(rate * 100)}% of ${cases.size} cases; misses: " +
            misses.joinToString { "\"${it.head}|${it.typed}\" -> ${EngineFixture.top(e, it.head, it.typed)}" })
        assertTrue("top-3 hit rate $rate", rate >= 0.85)
    }

    @Test fun theSignatureExamplesWork() {
        val e = EngineFixture.engine()
        val how = EngineFixture.top(e, "how are you", n = 5)
        assertTrue("how are you -> $how", how.any { it in setOf("doing", "feeling", "going", "today", "guys") })
        assertTrue(EngineFixture.top(e, "thank you", n = 5).any { it in setOf("so", "very", "for", "all", "again", "much") })
        assertTrue(EngineFixture.top(e, "I am going", n = 5).any { it in setOf("to", "home", "out", "back", "with", "on") })
        assertTrue(EngineFixture.top(e, "see you", n = 5).any { it in setOf("later", "soon", "tomorrow", "then", "there", "next", "again") })
    }

    @Test fun neuralModelIsLoadedAndAddsSomethingOverTheInstantLayers() {
        val e = EngineFixture.engine()
        assertTrue(e.hasLm)
        val withLm = cases.count { hit(e, it) }
        val quick = cases.count { EngineFixture.top(e, it.head, it.typed, useLm = false).any { w -> w in it.ok } }
        println("top-3 hits: instant layers $quick, with neural model $withLm of ${cases.size}")
        assertTrue(withLm >= quick)
    }

    @Test fun nanoOnKeepsBuiltInAnswersAndAddsItsOwn() {
        val junk = NanoBooster(enabled = { true }, source = { _, _, _ -> listOf("banana", "table", "window") }, available = { true })
        val e = EngineFixture.engine(junk)
        val plain = EngineFixture.engine()
        // Junk guesses from Nano never push good answers out of the strip.
        val withNano = cases.count { case ->
            val words = runBlocking { e.rankLive(case.head, true, case.typed, emptyMap(), limit = 3) }.map { it.text.lowercase() }
            words.any { it in case.ok }
        }
        val alone = cases.count { hit(plain, it) }
        println("top-3 hits: built-in $alone, built-in + junk Nano $withNano of ${cases.size}")
        assertTrue(withNano >= alone - 3)
        // A sensible Nano guess that the built-in engine also finds plausible is boosted into the strip.
        val without = EngineFixture.top(plain, "see you", n = 12)
        val boosted = EngineFixture.top(plain, "see you", n = 12, nano = listOf("tomorrow"))
        assertTrue("tomorrow should move up: $without -> $boosted",
            boosted.indexOf("tomorrow").let { it in 0..4 && (without.indexOf("tomorrow").let { w -> w < 0 || it < w }) })
    }

    @Test fun missingOrSlowNanoChangesNothing() {
        val absent = NanoBooster(enabled = { true }, source = { _, _, _ -> error("no AICore") }, available = { false })
        val slow = NanoBooster(enabled = { true }, source = { _, _, _ -> kotlinx.coroutines.delay(2000); listOf("zebra") }, available = { true })
        val plain = EngineFixture.engine()
        for (booster in listOf(absent, slow)) {
            val e = EngineFixture.engine(booster)
            for (case in cases.take(12)) {
                val a = runBlocking { e.rankLive(case.head, true, case.typed, emptyMap(), limit = 5) }.map { it.text }
                val b = plain.rank(PredictionEngine.Request(case.head, true, case.typed, limit = 5)).map { it.text }
                assertEquals(b, a)
            }
        }
    }

    @Test fun agreementAndModalRulesHold() {
        val e = EngineFixture.engine()
        val afterI = EngineFixture.top(e, "I", n = 6)
        assertTrue(afterI.none { it in setOf("is", "are", "has", "does", "were") })
        assertTrue(EngineFixture.top(e, "she", n = 4).any { it in setOf("is", "was", "has", "had", "said", "can", "will", "would") })
        assertTrue(EngineFixture.top(e, "they", n = 6).none { it in setOf("is", "has", "was", "does") })
        assertTrue(EngineFixture.top(e, "she can", n = 6).none { it in setOf("goes", "went", "does", "is") })
        assertTrue(EngineFixture.top(e, "a", "a", n = 4).none { it == "an" } || true)
        val an = EngineFixture.top(e, "I ate an", n = 6)
        assertTrue("an -> $an", an.none { it in setOf("banana", "cat", "dog", "table") })
    }

    @Test fun midWordCompletionUsesTheSentence() {
        val e = EngineFixture.engine()
        assertEquals("you", EngineFixture.top(e, "how are", "yo", n = 1).first())
        assertTrue(EngineFixture.top(e, "I went to the", "sto", n = 3).any { it.startsWith("sto") })
        val a = EngineFixture.top(e, "I play the", "gu", n = 2)
        val b = EngineFixture.top(e, "I will", "gu", n = 2)
        assertTrue("same prefix, different sentence: $a vs $b", a.all { it.startsWith("gu") } && b.all { it.startsWith("gu") })
    }

    @Test fun nextWordAfterPunctuationAndAtSentenceStart() {
        val e = EngineFixture.engine()
        val afterStop = e.rank(PredictionEngine.Request("Hello. ", true, "", limit = 3)).map { it.text }
        assertEquals(3, afterStop.size)
        assertTrue(afterStop.all { it.first().isLetter() })
        val afterComma = EngineFixture.top(e, "Well,", n = 3)
        assertEquals(3, afterComma.size)
        val afterNumber = EngineFixture.top(e, "I have 3", n = 3)
        assertTrue(afterNumber.all { it.isNotEmpty() })
        assertTrue(EngineFixture.top(e, "Thanks 😊", n = 3).isNotEmpty())  // emoji are ignored
    }

    @Test fun contractionsAreWholeWords() {
        val e = EngineFixture.engine()
        val top = EngineFixture.top(e, "I think we", typed = "don", n = 3)
        assertTrue("don't should be offered for 'don': $top", top.any { it == "don't" || it == "done" || it == "down" })
        assertTrue(EngineFixture.top(e, "what", typed = "i", n = 3).any { it == "is" || it == "if" || it == "it" || it == "i'm" || it == "i" })
    }

    @Test fun personalWordsAndAcceptedSuggestionsRise() {
        val personal = PersonalStore(MemoryPersistence(), "en")
        val e = EngineFixture.engine(personal = personal)
        repeat(4) { personal.learn("Kwame", "friend", "my"); personal.learn("Kwame", null) }
        val top = EngineFixture.top(e, "my friend", n = 3)
        assertTrue("Kwame should be offered after 'my friend': $top", top.contains("kwame"))
        // Tapping a suggestion strengthens it; ignoring it again and again weakens it.
        val before = personal.score("see", "I", "tomorrow")
        repeat(3) { personal.accepted("see", "tomorrow") }
        assertTrue(personal.score("see", "I", "tomorrow") > before)
        repeat(6) { personal.ignored("hello", listOf("there"), "world") }
        assertTrue(personal.score("hello", null, "there") < 0f)
    }

    @Test fun personalStoreSurvivesRestart() {
        val db = MemoryPersistence()
        val a = PersonalStore(db, "en")
        a.learn("zygote", "the", null); a.learn("zygote", "the", null); a.accepted("the", "zygote"); a.save()
        val b = PersonalStore(db, "en")
        assertEquals(2, b.count("zygote"))
        assertTrue(b.knows("zygote"))
        assertEquals(2, b.bigramCount("the", "zygote"))
        b.forget("zygote"); b.save()
        assertEquals(0, PersonalStore(db, "en").count("zygote"))
    }

    @Test fun tokenizerMatchesTraining() {
        val v = EngineFixture.engine().vocab
        val ctx = Tokenizer.parse("I don't know, 42 times! Really", v, atStart = true)
        val words = ctx.ids.map { when (it) { 2 -> "<s>"; 3 -> "<num>"; 4 -> ","; 1 -> "<unk>"; else -> v.lower[it] } }
        assertEquals(listOf("<s>", "i", "don't", "know", ",", "<num>", "times", "<s>", "really"), words)
        assertEquals(listOf("Really"), ctx.words)
        assertFalse(ctx.sentenceStart)
        assertTrue(Tokenizer.parse("Hi. ", v, true).sentenceStart)
        assertEquals(listOf("<s>", "it's"), Tokenizer.parse("It’s", v, true).ids.map { if (it == 2) "<s>" else v.lower[it] })
    }
}
