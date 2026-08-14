package com.example.watcher.data.model

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

private val classroomKnowledgeTreeGson = Gson()
private val classroomKnowledgeFrameRefsType = object : TypeToken<List<ClassroomKnowledgeFrameRef>>() {}.type

fun ClassroomKnowledgeTree?.toPersistedClassroomKnowledgeTreeJson(): String {
    return this?.let { classroomKnowledgeTreeGson.toJson(it) }.orEmpty()
}

fun List<ClassroomKnowledgeFrameRef>.toPersistedClassroomKnowledgeFrameRefsJson(): String {
    return takeIf { it.isNotEmpty() }?.let { classroomKnowledgeTreeGson.toJson(it) }.orEmpty()
}

fun VideoProcessRun.persistedClassroomKnowledgeTree(): ClassroomKnowledgeTree? {
    val json = classroomKnowledgeTreeJson.takeIf(String::isNotBlank) ?: return null
    return runCatching {
        classroomKnowledgeTreeGson.fromJson(json, ClassroomKnowledgeTree::class.java)
    }.getOrNull()?.takeIf { it.nodes.isNotEmpty() }
}

fun VideoProcessRun.persistedClassroomKnowledgeFrameRefs(): List<ClassroomKnowledgeFrameRef> {
    val json = classroomKnowledgeFrameRefsJson.takeIf(String::isNotBlank) ?: return emptyList()
    return runCatching {
        classroomKnowledgeTreeGson.fromJson<List<ClassroomKnowledgeFrameRef>>(json, classroomKnowledgeFrameRefsType)
    }.getOrNull().orEmpty()
}
