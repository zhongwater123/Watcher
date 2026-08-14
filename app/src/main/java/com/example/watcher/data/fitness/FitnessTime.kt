package com.example.watcher.data.fitness

private const val FITNESS_DAY_MS = 86_400_000L

fun currentFitnessEpochDay(): Long = System.currentTimeMillis() / FITNESS_DAY_MS
