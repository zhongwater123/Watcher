package com.example.watcher.data.intentrouter

import com.google.gson.JsonParser
import java.util.Locale

object IntentRouterResponseParser {
    private const val MIN_CONFIDENCE = 0.55f
    private const val LOOSE_OUTPUT_CONFIDENCE = 0.62f

    fun parse(
        rawText: String,
        minConfidence: Float = MIN_CONFIDENCE
    ): IntentRouterParseResult {
        val json = extractJsonObject(rawText)
            ?: return parseLooseRouteOutput(rawText, minConfidence)

        val payload = runCatching {
            JsonParser.parseString(json).asJsonObject
        }.getOrElse {
            return parseLooseRouteOutput(rawText, minConfidence)
        }

        val routeId = listOf("routeId", "route_id", "route")
            .firstNotNullOfOrNull { key ->
                payload.get(key)?.asString?.trim()?.takeIf { it.isNotBlank() }
            }
            .orEmpty()
        if (routeId.isBlank()) {
            return IntentRouterParseResult.Failure("Missing routeId")
        }

        val route = when (val match = resolveRouteReference(routeId)) {
            is RouteReferenceMatch.Success -> match.route
            RouteReferenceMatch.Ambiguous -> return IntentRouterParseResult.Failure("Ambiguous routeId")
            RouteReferenceMatch.Missing -> return IntentRouterParseResult.Failure("Unknown routeId")
        }

        val confidence = runCatching {
            payload.get("confidence")?.asFloat ?: 0f
        }.getOrDefault(0f)

        if (confidence < minConfidence) {
            return IntentRouterParseResult.Failure("Low confidence")
        }

        return IntentRouterParseResult.Success(
            IntentRouterDecision(
                route = route,
                confidence = confidence.coerceIn(0f, 1f)
            )
        )
    }

    private fun parseLooseRouteOutput(
        rawText: String,
        minConfidence: Float
    ): IntentRouterParseResult {
        val trimmed = stripMarkdownFence(rawText.trim())
        if (trimmed.isBlank()) {
            return IntentRouterParseResult.Failure("No JSON object found")
        }

        val route = when (val match = resolveRouteReference(trimmed)) {
            is RouteReferenceMatch.Success -> match.route
            RouteReferenceMatch.Ambiguous -> return IntentRouterParseResult.Failure("Ambiguous routeId")
            RouteReferenceMatch.Missing -> return IntentRouterParseResult.Failure("No routeId found")
        }

        val confidence = extractLooseConfidence(trimmed.lowercase(Locale.ROOT)) ?: LOOSE_OUTPUT_CONFIDENCE
        if (confidence < minConfidence) {
            return IntentRouterParseResult.Failure("Low confidence")
        }

        return IntentRouterParseResult.Success(
            IntentRouterDecision(
                route = route,
                confidence = confidence.coerceIn(0f, 1f)
            )
        )
    }

    private fun resolveRouteReference(value: String): RouteReferenceMatch {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return RouteReferenceMatch.Missing
        val normalized = trimmed.lowercase(Locale.ROOT)

        val strongMatches = IntentRouteCatalog.routes.filter { route ->
            containsRouteToken(normalized, route.id.wireId) || trimmed.contains(route.title)
        }
        if (strongMatches.size > 1) return RouteReferenceMatch.Ambiguous
        if (strongMatches.size == 1) return RouteReferenceMatch.Success(strongMatches.single())

        val keywordScores = IntentRouteCatalog.routes
            .map { route -> route to keywordScore(route, rawValue = trimmed, normalizedValue = normalized) }
            .filter { (_, score) -> score > 0 }
            .sortedByDescending { (_, score) -> score }

        val best = keywordScores.firstOrNull() ?: return RouteReferenceMatch.Missing
        val hasTie = keywordScores.drop(1).any { (_, score) -> score == best.second }
        if (hasTie) return RouteReferenceMatch.Ambiguous
        return RouteReferenceMatch.Success(best.first)
    }

    private fun keywordScore(
        route: IntentRouteDefinition,
        rawValue: String,
        normalizedValue: String
    ): Int {
        return route.keywords.count { keyword ->
            if (keyword.isAsciiToken()) {
                containsRouteToken(normalizedValue, keyword.lowercase(Locale.ROOT))
            } else {
                rawValue.contains(keyword)
            }
        }
    }

    private fun containsRouteToken(value: String, routeId: String): Boolean {
        val pattern = Regex("""(^|[^a-z0-9_])${Regex.escape(routeId)}([^a-z0-9_]|$)""")
        return pattern.containsMatchIn(value)
    }

    private fun String.isAsciiToken(): Boolean {
        return all { character ->
            character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' || character == '_'
        }
    }

    private sealed interface RouteReferenceMatch {
        data class Success(val route: IntentRouteDefinition) : RouteReferenceMatch
        object Missing : RouteReferenceMatch
        object Ambiguous : RouteReferenceMatch
    }


    private fun extractLooseConfidence(value: String): Float? {
        val match = Regex("""(?:confidence|score)\s*[:=]\s*([01](?:\.\d+)?)""")
            .find(value)
            ?: return null
        return match.groupValues.getOrNull(1)?.toFloatOrNull()
    }

    private fun extractJsonObject(rawText: String): String? {
        val trimmed = rawText.trim()
        if (trimmed.isBlank()) return null

        val withoutFence = stripMarkdownFence(trimmed)

        val firstBrace = withoutFence.indexOf('{')
        val lastBrace = withoutFence.lastIndexOf('}')
        if (firstBrace < 0 || lastBrace <= firstBrace) return null
        return withoutFence.substring(firstBrace, lastBrace + 1)
    }

    private fun stripMarkdownFence(value: String): String {
        if (!value.startsWith("```")) return value
        val firstLineEnd = value.indexOf('\n')
        if (firstLineEnd < 0) return value
        val closingFenceStart = value.lastIndexOf("```")
        if (closingFenceStart <= firstLineEnd) return value
        return value.substring(firstLineEnd + 1, closingFenceStart).trim()
    }
}
