package com.example.watcher.data.intentrouter

import java.util.Locale

object IntentRouteKeywordMatcher {
    fun match(userInput: String, minScore: Int = 1): IntentRouterDecision? {
        val normalizedInput = userInput.trim().lowercase(Locale.ROOT)
        if (normalizedInput.isBlank()) return null

        val scored = IntentRouteCatalog.routes
            .map { route -> route to route.score(normalizedInput) }
            .filter { (_, score) -> score >= minScore }
            .sortedByDescending { (_, score) -> score }

        val best = scored.firstOrNull() ?: return null
        val hasTie = scored.drop(1).any { (_, score) -> score == best.second }
        if (hasTie) return null

        return IntentRouterDecision(
            route = best.first,
            confidence = confidenceForScore(best.second),
            source = IntentRouterDecisionSource.LocalKeyword
        )
    }

    private fun confidenceForScore(score: Int): Float {
        return (0.68f + score * 0.04f).coerceIn(0f, 0.88f)
    }

    private fun IntentRouteDefinition.score(input: String): Int {
        return keywords.count { keyword ->
            input.contains(keyword.lowercase(Locale.ROOT))
        }
    }
}
