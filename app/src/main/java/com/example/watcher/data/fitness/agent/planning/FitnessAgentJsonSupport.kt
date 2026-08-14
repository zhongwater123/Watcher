package com.example.watcher.data.fitness.agent.planning

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal fun String.extractFitnessAgentJsonObject(): JsonObject {
    val start = indexOf('{')
    val end = lastIndexOf('}')
    require(start >= 0 && end > start) { "No JSON object found." }
    return JsonParser.parseString(substring(start, end + 1)).asJsonObject
}

internal fun JsonObject.fitnessString(name: String, defaultValue: String): String {
    return runCatching {
        get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.takeIf(String::isNotBlank)
    }.getOrNull() ?: defaultValue
}

internal fun JsonObject.fitnessObjectOrSelf(name: String): JsonObject {
    val element = get(name)
    return if (element != null && element.isJsonObject) element.asJsonObject else this
}

internal fun JsonObject.fitnessObjectOrEmpty(vararg names: String): JsonObject {
    names.forEach { name ->
        val element = get(name)
        if (element != null && element.isJsonObject) return element.asJsonObject
    }
    return JsonObject()
}

internal fun JsonObject.fitnessArrayOrEmpty(vararg names: String): JsonArray {
    names.forEach { name ->
        val element = get(name)
        if (element != null && element.isJsonArray) return element.asJsonArray
    }
    return JsonArray()
}

internal fun JsonObject.fitnessIntOrNull(vararg names: String): Int? {
    names.forEach { name ->
        val element = get(name) ?: return@forEach
        val value = runCatching {
            when {
                element.isJsonPrimitive && element.asJsonPrimitive.isNumber -> element.asInt
                element.isJsonPrimitive && element.asJsonPrimitive.isString -> element.asString.toIntOrNull()
                else -> null
            }
        }.getOrNull()
        if (value != null) return value
    }
    return null
}

internal fun JsonObject.fitnessFloatOrNull(vararg names: String): Float? {
    names.forEach { name ->
        val element = get(name) ?: return@forEach
        val value = runCatching {
            when {
                element.isJsonPrimitive && element.asJsonPrimitive.isNumber -> element.asFloat
                element.isJsonPrimitive && element.asJsonPrimitive.isString -> element.asString.toFloatOrNull()
                else -> null
            }
        }.getOrNull()
        if (value != null) return value
    }
    return null
}

internal fun JsonObject.fitnessRangeArray(vararg names: String): JsonArray {
    names.forEach { name ->
        val element = get(name) ?: return@forEach
        when {
            element.isJsonArray -> return element.asJsonArray
            element.isJsonPrimitive && element.asJsonPrimitive.isNumber -> {
                return JsonArray().apply {
                    add(element.asInt)
                    add(element.asInt)
                }
            }
            element.isJsonPrimitive && element.asJsonPrimitive.isString -> {
                val values = Regex("""\d+""")
                    .findAll(element.asString)
                    .mapNotNull { it.value.toIntOrNull() }
                    .take(2)
                    .toList()
                if (values.isNotEmpty()) {
                    return JsonArray().apply {
                        add(values.first())
                        add(values.getOrElse(1) { values.first() })
                    }
                }
            }
        }
    }
    return JsonArray()
}

internal fun JsonElement.asFitnessExerciseObject(): JsonObject {
    if (isJsonObject) return asJsonObject
    return JsonObject().also { item ->
        if (isJsonPrimitive) {
            runCatching { asString }.getOrNull()?.takeIf(String::isNotBlank)?.let {
                item.addProperty("exercise", it)
            }
        }
    }
}

internal fun JsonArray.fitnessNumberAt(index: Int): Number? {
    if (index !in 0 until size()) return null
    val element = get(index)
    return if (element.isJsonPrimitive && element.asJsonPrimitive.isNumber) element.asNumber else null
}
