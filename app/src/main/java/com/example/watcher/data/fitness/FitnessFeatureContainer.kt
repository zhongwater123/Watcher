package com.example.watcher.data.fitness

import android.app.Application
import com.example.watcher.data.fitness.agent.feedback.realtime.FitnessRealtimeVlmAnalyzer
import com.example.watcher.data.fitness.agent.planning.FitnessStrategyPlanningAgent
import com.example.watcher.data.fitness.agent.planning.FitnessWalletAgentModelClient
import com.example.watcher.data.fitness.agent.planning.FitnessWorkoutPlanningAgent
import com.example.watcher.data.local.AppDatabase
import com.example.watcher.data.remote.ArkStreamingClient
import com.example.watcher.data.repository.ExerciseLibraryRepository
import com.example.watcher.data.repository.FitnessCompanionRepository
import com.example.watcher.data.repository.FitnessRepCounterRepository
import com.example.watcher.data.repository.LlmWalletRepository

class FitnessFeatureContainer(
    application: Application,
    database: AppDatabase,
    llmWalletRepository: LlmWalletRepository
) {
    private val fitnessDao = database.fitnessCompanionDao()
    private val modelClient = FitnessWalletAgentModelClient(llmWalletRepository)

    val companionRepository = FitnessCompanionRepository(
        dao = fitnessDao,
        strategyGenerator = FitnessStrategyPlanningAgent(modelClient),
        workoutPlanGenerator = FitnessWorkoutPlanningAgent(modelClient)
    )

    val visualFeedbackAnalyzer = FitnessRealtimeVlmAnalyzer(
        dao = fitnessDao,
        streamingClient = ArkStreamingClient(),
        llmWalletRepository = llmWalletRepository
    )

    val repCounterRepository = FitnessRepCounterRepository(
        context = application,
        dao = fitnessDao
    )

    val exerciseLibraryRepository = ExerciseLibraryRepository(
        context = application,
        dao = database.exerciseLibraryDao()
    )

    val videoStreamSettings = database.videoStreamSettingsDao().getSettings()
}
