package com.gr4nt.iboard.predict

/** Small, conservative English constraints for ranking; never rewrites a valid typed word. */
object EnglishGrammar {
    private val subjects = mapOf(
        "i" to listOf("am", "have", "will", "can", "think", "want", "need"),
        "you" to listOf("are", "have", "can", "will", "want", "need"),
        "we" to listOf("are", "have", "can", "will", "want", "need"),
        "they" to listOf("are", "have", "can", "will", "want", "need"),
        "he" to listOf("is", "has", "can", "will", "wants", "needs"),
        "she" to listOf("is", "has", "can", "will", "wants", "needs"),
        "it" to listOf("is", "has", "can", "will", "seems", "looks"),
    )
    private val be = setOf("am", "is", "are", "was", "were")
    private val plural = setOf("you", "we", "they")
    private val singular = setOf("he", "she", "it")
    private val auxiliaries = setOf("can", "could", "will", "would", "shall", "should", "may", "might", "must", "do", "does", "did", "don't", "doesn't", "didn't", "cannot", "can't", "won't", "shouldn't", "couldn't", "wouldn't", "mustn't")
    private val forms = mapOf(
        "go" to listOf("goes", "went", "gone", "going"),
        "do" to listOf("does", "did", "done", "doing"),
        "have" to listOf("has", "had", "had", "having"),
        "be" to listOf("is", "was", "been", "being"),
        "eat" to listOf("eats", "ate", "eaten", "eating"),
        "see" to listOf("sees", "saw", "seen", "seeing"),
        "write" to listOf("writes", "wrote", "written", "writing"),
        "take" to listOf("takes", "took", "taken", "taking"),
        "make" to listOf("makes", "made", "made", "making"),
        "come" to listOf("comes", "came", "come", "coming"),
        "get" to listOf("gets", "got", "gotten", "getting"),
        "know" to listOf("knows", "knew", "known", "knowing"),
        "want" to listOf("wants", "wanted", "wanted", "wanting"),
        "need" to listOf("needs", "needed", "needed", "needing"),
        "work" to listOf("works", "worked", "worked", "working"),
        "like" to listOf("likes", "liked", "liked", "liking"),
    )
    private val inflected = forms.values.flatten().toSet() + setOf("am", "are", "were")
    private val participles = forms.values.map { it[2] }.toSet() + "got"
    private fun key(s: String?) = s.orEmpty().lowercase(java.util.Locale.ROOT).replace('’', '\'')

    fun candidates(previous: String?, before: String?): List<String> {
        val p = key(previous)
        return subjects[p] ?: when {
            p in be -> listOf("going", "doing", "ready", "sure", "happy", "sorry", "not", "a", "the")
            p in setOf("have", "has", "had") -> listOf("been", "done", "seen", "made", "a", "the", "to")
            p in auxiliaries -> listOf("be", "have", "go", "do", "get", "see", "help", "take", "make")
            else -> emptyList()
        }
    }

    fun adjustment(previous: String?, before: String?, word: String): Float {
        val p = key(previous); val b = key(before); val w = key(word)
        if (p.isEmpty()) return 0f
        if (p == "i" && w in setOf("is", "are", "were", "has", "does")) return -18f
        if (p in singular && w in setOf("am", "are", "were", "have", "do")) return -18f
        if (p in plural && w in setOf("am", "is", "was", "has", "does")) return -18f
        if (p in singular && w in forms.keys) return -9f
        if ((p == "i" || p in plural) && w in forms.values.map { it[0] }) return -9f
        if (p in auxiliaries && w in inflected && w !in forms.keys) return -14f
        if (p in setOf("have", "has", "had") && (b in subjects || b in auxiliaries) &&
            (w in forms.keys || w in inflected) && w !in participles) return -12f
        // Only reject clearly finite verb forms after be; nouns/adjectives stay available.
        if (p in be && w in setOf("am", "is", "are", "was", "were", "go", "goes", "went", "does", "did", "have", "has")) return -14f
        if (p == "a" || p == "an") {
            if (w.isEmpty() || !w[0].isLetter() || w.length == 1 || word.all { !it.isLetter() || it.isUpperCase() }) return 0f
            val vowelSound = when {
                listOf("honest", "honor", "honour", "hour", "heir").any { w.startsWith(it) } -> true
                listOf("university", "universal", "united", "union", "unit", "unique", "uniform", "unicorn", "user", "use", "usual", "euro", "one", "once").any { w.startsWith(it) } -> false
                else -> w[0] in "aeiou"
            }
            return if ((p == "an") == vowelSound) 1f else -5f
        }
        if (w in subjects[p].orEmpty()) return 1.5f
        return 0f
    }
}
