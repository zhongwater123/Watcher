package com.example.watcher.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.watcher.data.model.MonitorTask
import com.example.watcher.data.model.MonitorEventEntity
import com.example.watcher.data.model.MonitorMediaEntity
import com.example.watcher.data.model.MonitorRun
import com.example.watcher.data.model.MonitorTemplateEntity
import com.example.watcher.data.model.CouncilExpertEntity
import com.example.watcher.data.model.CouncilKnowledgeEntity
import com.example.watcher.data.model.CouncilTemplateEntity
import com.example.watcher.data.model.TimelineEventEntity
import com.example.watcher.data.model.VideoAiTraceEventEntity
import com.example.watcher.data.model.VideoAudioAssetEntity
import com.example.watcher.data.model.VideoProcessRun
import com.example.watcher.data.model.VideoProcessTask
import com.example.watcher.data.model.VideoRemoteFileBindingEntity
import com.example.watcher.data.model.VideoSegmentRun
import com.example.watcher.data.model.VideoSpeechTranscriptEntity
import com.example.watcher.data.model.VideoStreamSettings
import com.example.watcher.data.model.AiAudienceEntity
import com.example.watcher.data.model.AiAudienceMessageEntity
import com.example.watcher.data.model.BlackboardDay
import com.example.watcher.data.model.BlackboardEntry
import com.example.watcher.data.model.BlackboardObservationItem
import com.example.watcher.data.model.BehaviorClaim
import com.example.watcher.data.model.BehaviorReasoningLog
import com.example.watcher.data.model.ClassroomNoteFollowupEntity
import com.example.watcher.data.model.ClassroomTranscriptConsumptionEntity
import com.example.watcher.data.model.CouncilExpertDefaults
import com.example.watcher.data.model.FitnessAgentRunEntity
import com.example.watcher.data.model.FitnessExerciseEntity
import com.example.watcher.data.model.FitnessExerciseInstructionEntity
import com.example.watcher.data.model.FitnessExerciseInstructionStepEntity
import com.example.watcher.data.model.FitnessExerciseLibraryMetaEntity
import com.example.watcher.data.model.FitnessExerciseResultEntity
import com.example.watcher.data.model.FitnessMediaAssetEntity
import com.example.watcher.data.model.FitnessRealtimeFeedbackEventEntity
import com.example.watcher.data.model.FitnessRepEventEntity
import com.example.watcher.data.model.FitnessExerciseSecondaryMuscleEntity
import com.example.watcher.data.model.FitnessStrategyGoalEntity
import com.example.watcher.data.model.FitnessStrategySpecEntity
import com.example.watcher.data.model.FitnessSessionResultEntity
import com.example.watcher.data.model.FitnessUserProfileEntity
import com.example.watcher.data.model.FitnessWeeklyLedgerEntity
import com.example.watcher.data.model.FitnessWorkoutExerciseEntity
import com.example.watcher.data.model.FitnessWorkoutLogEntity
import com.example.watcher.data.model.FitnessWorkoutPlanEntity
import com.example.watcher.data.model.LlmProviderEntity
import com.example.watcher.data.model.ObservationGoal
import com.example.watcher.data.model.PortraitDimension
import com.example.watcher.data.model.SceneProfile
import com.example.watcher.data.model.VideoTemplateEntity

@Database(
    entities = [
        MonitorTask::class,
        MonitorRun::class,
        MonitorEventEntity::class,
        MonitorMediaEntity::class,
        VideoStreamSettings::class,
        VideoProcessTask::class,
        VideoProcessRun::class,
        VideoSegmentRun::class,
        VideoAudioAssetEntity::class,
        VideoRemoteFileBindingEntity::class,
        VideoSpeechTranscriptEntity::class,
        ClassroomTranscriptConsumptionEntity::class,
        ClassroomNoteFollowupEntity::class,
        VideoAiTraceEventEntity::class,
        TimelineEventEntity::class,
        MonitorTemplateEntity::class,
        VideoTemplateEntity::class,
        CouncilTemplateEntity::class,
        CouncilExpertEntity::class,
        LlmProviderEntity::class,
        AiAudienceEntity::class,
        AiAudienceMessageEntity::class,
        CouncilKnowledgeEntity::class,
        BlackboardDay::class,
        BlackboardEntry::class,
        BlackboardObservationItem::class,
        PortraitDimension::class,
        BehaviorClaim::class,
        BehaviorReasoningLog::class,
        ObservationGoal::class,
        SceneProfile::class,
        FitnessUserProfileEntity::class,
        FitnessMediaAssetEntity::class,
        FitnessStrategyGoalEntity::class,
        FitnessStrategySpecEntity::class,
        FitnessWeeklyLedgerEntity::class,
        FitnessWorkoutPlanEntity::class,
        FitnessWorkoutExerciseEntity::class,
        FitnessWorkoutLogEntity::class,
        FitnessSessionResultEntity::class,
        FitnessExerciseResultEntity::class,
        FitnessRealtimeFeedbackEventEntity::class,
        FitnessRepEventEntity::class,
        FitnessAgentRunEntity::class,
        FitnessExerciseEntity::class,
        FitnessExerciseInstructionEntity::class,
        FitnessExerciseInstructionStepEntity::class,
        FitnessExerciseSecondaryMuscleEntity::class,
        FitnessExerciseLibraryMetaEntity::class,
        com.example.watcher.data.local.pose.PoseVideoSession::class
    ],
    version = 70,
    exportSchema = false
)
@TypeConverters(DatabaseConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun monitorTaskDao(): MonitorTaskDao
    abstract fun monitorRunDao(): MonitorRunDao
    abstract fun monitorEventDao(): MonitorEventDao
    abstract fun monitorMediaDao(): MonitorMediaDao
    abstract fun videoStreamSettingsDao(): VideoStreamSettingsDao
    abstract fun videoProcessTaskDao(): VideoProcessTaskDao
    abstract fun videoProcessRunDao(): VideoProcessRunDao
    abstract fun videoSegmentRunDao(): VideoSegmentRunDao
    abstract fun videoAudioAssetDao(): VideoAudioAssetDao
    abstract fun videoRemoteFileBindingDao(): VideoRemoteFileBindingDao
    abstract fun videoSpeechTranscriptDao(): VideoSpeechTranscriptDao
    abstract fun classroomTranscriptConsumptionDao(): ClassroomTranscriptConsumptionDao
    abstract fun classroomNoteFollowupDao(): ClassroomNoteFollowupDao
    abstract fun videoAiTraceDao(): VideoAiTraceDao
    abstract fun timelineEventDao(): TimelineEventDao
    abstract fun templateDao(): TemplateDao
    abstract fun councilExpertDao(): CouncilExpertDao
    abstract fun llmProviderDao(): LlmProviderDao
    abstract fun aiAudienceDao(): AiAudienceDao
    abstract fun aiAudienceMessageDao(): AiAudienceMessageDao
    abstract fun councilKnowledgeDao(): CouncilKnowledgeDao
    abstract fun blackboardDao(): BlackboardDao
    abstract fun portraitDao(): PortraitDao
    abstract fun behaviorModelDao(): BehaviorModelDao
    abstract fun sceneProfileDao(): SceneProfileDao
    abstract fun poseVideoSessionDao(): PoseVideoSessionDao
    abstract fun fitnessCompanionDao(): FitnessCompanionDao
    abstract fun exerciseLibraryDao(): ExerciseLibraryDao

    companion object {
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    ALTER TABLE `video_process_tasks`
                    ADD COLUMN `finalSummaryPrompt` TEXT NOT NULL DEFAULT ''
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    ALTER TABLE `video_process_runs`
                    ADD COLUMN `mergedVideoPath` TEXT
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    ALTER TABLE `monitor_tasks`
                    ADD COLUMN `baselineImagePath` TEXT
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    ALTER TABLE `monitor_tasks`
                    ADD COLUMN `monitorMode` TEXT NOT NULL DEFAULT 'SceneBaseline'
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    ALTER TABLE `monitor_tasks`
                    ADD COLUMN `targetTrigger` TEXT NOT NULL DEFAULT 'OnAppear'
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    ALTER TABLE `monitor_tasks`
                    ADD COLUMN `baselineSource` TEXT NOT NULL DEFAULT 'CapturedFrame'
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    ALTER TABLE `monitor_runs`
                    ADD COLUMN `monitorMode` TEXT NOT NULL DEFAULT 'SceneBaseline'
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    ALTER TABLE `monitor_runs`
                    ADD COLUMN `targetTrigger` TEXT NOT NULL DEFAULT 'OnAppear'
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    ALTER TABLE `monitor_runs`
                    ADD COLUMN `baselineSource` TEXT NOT NULL DEFAULT 'CapturedFrame'
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    ALTER TABLE `video_stream_settings`
                    ADD COLUMN `deviceProfile` TEXT NOT NULL DEFAULT 'Esp32Camera'
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    ALTER TABLE `video_stream_settings`
                    ADD COLUMN `deviceToken` TEXT NOT NULL DEFAULT ''
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    ALTER TABLE `video_stream_settings`
                    ADD COLUMN `ownerId` TEXT NOT NULL DEFAULT ''
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    ALTER TABLE `video_stream_settings`
                    ADD COLUMN `preferredWifiSsid` TEXT NOT NULL DEFAULT ''
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `monitor_templates` (
                        `templateId` TEXT NOT NULL,
                        `label` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `userRequirement` TEXT NOT NULL,
                        `originalSceneDescription` TEXT NOT NULL,
                        `checkIntervalSeconds` INTEGER NOT NULL,
                        `promptTemplate` TEXT NOT NULL,
                        `monitorMode` TEXT NOT NULL,
                        `targetTrigger` TEXT NOT NULL,
                        `baselineSource` TEXT NOT NULL,
                        `isDefault` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`templateId`)
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `video_templates` (
                        `templateId` TEXT NOT NULL,
                        `label` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `taskCategory` TEXT NOT NULL,
                        `strategyReason` TEXT NOT NULL,
                        `userRequirement` TEXT NOT NULL,
                        `sceneContext` TEXT NOT NULL,
                        `segmentAnalysisPrompt` TEXT NOT NULL,
                        `finalSummaryPrompt` TEXT NOT NULL,
                        `recordingDurationSeconds` INTEGER NOT NULL,
                        `segmentDurationSeconds` INTEGER NOT NULL,
                        `captureIntervalSeconds` INTEGER NOT NULL,
                        `samplingFps` INTEGER NOT NULL,
                        `autoStartStreamingOutput` INTEGER NOT NULL,
                        `finalSummaryEnabled` INTEGER NOT NULL,
                        `isDefault` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`templateId`)
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `llm_providers` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `endpoint` TEXT NOT NULL,
                        `apiKey` TEXT NOT NULL,
                        `modelName` TEXT NOT NULL,
                        `enabled` INTEGER NOT NULL DEFAULT 1,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `ai_audiences` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `persona` TEXT NOT NULL,
                        `providerId` TEXT NOT NULL,
                        `enabled` INTEGER NOT NULL DEFAULT 1,
                        `heartbeatIntervalSeconds` INTEGER NOT NULL DEFAULT 15,
                        `includeFrame` INTEGER NOT NULL DEFAULT 0,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `ai_audience_messages` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `audienceId` INTEGER NOT NULL,
                        `audienceName` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `mentionedAudienceId` INTEGER,
                        `mentionedAudienceName` TEXT,
                        `triggerType` TEXT NOT NULL DEFAULT 'heartbeat',
                        `timestamp` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        FOREIGN KEY(`audienceId`) REFERENCES `ai_audiences`(`id`) ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_audience_messages_audienceId` ON `ai_audience_messages` (`audienceId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_audience_messages_timestamp` ON `ai_audience_messages` (`timestamp`)")
            }
        }

        private val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `ai_audiences` ADD COLUMN `personalMemory` TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `ai_audiences` ADD COLUMN `audienceType` TEXT NOT NULL DEFAULT 'Agent'"
                )
            }
        }

        private val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `ai_audiences` ADD COLUMN `socialArchetype` TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE `ai_audiences` ADD COLUMN `speakingStyle` TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE `ai_audiences` ADD COLUMN `spendingStyle` TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE `ai_audiences` ADD COLUMN `socialDrive` TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `ai_audiences` ADD COLUMN `agentStateJson` TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `council_templates` (
                        `templateId` TEXT NOT NULL,
                        `label` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `sceneType` TEXT NOT NULL,
                        `objective` TEXT NOT NULL,
                        `focus` TEXT NOT NULL,
                        `isDefault` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`templateId`)
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `council_experts` (
                        `role` TEXT NOT NULL,
                        `promptPersona` TEXT NOT NULL,
                        `providerId` TEXT NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`role`)
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `council_experts` ADD COLUMN `name` TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE `council_experts` ADD COLUMN `perspective` TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE `council_experts` ADD COLUMN `sortOrder` INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // No-op. Version 29 keeps the council_experts schema from version 28
                // and relies on reseeding defaults to backfill the new columns.
            }
        }

        private val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `council_experts_new` (
                        `expertId` TEXT NOT NULL,
                        `role` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `promptPersona` TEXT NOT NULL,
                        `perspective` TEXT NOT NULL,
                        `providerId` TEXT NOT NULL,
                        `expertKind` TEXT NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `selectedForCouncil` INTEGER NOT NULL,
                        `sortOrder` INTEGER NOT NULL,
                        `isSystemPreset` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`expertId`)
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    INSERT INTO `council_experts_new` (
                        `expertId`,
                        `role`,
                        `name`,
                        `description`,
                        `promptPersona`,
                        `perspective`,
                        `providerId`,
                        `expertKind`,
                        `enabled`,
                        `selectedForCouncil`,
                        `sortOrder`,
                        `isSystemPreset`,
                        `createdAt`,
                        `updatedAt`
                    )
                    SELECT
                        `role` AS `expertId`,
                        `role`,
                        `name`,
                        '' AS `description`,
                        `promptPersona`,
                        `perspective`,
                        `providerId`,
                        CASE
                            WHEN `role` = 'Synthesizer' THEN 'Synthesizer'
                            ELSE 'Specialist'
                        END AS `expertKind`,
                        `enabled`,
                        `enabled` AS `selectedForCouncil`,
                        `sortOrder`,
                        1 AS `isSystemPreset`,
                        `updatedAt` AS `createdAt`,
                        `updatedAt`
                    FROM `council_experts`
                    """.trimIndent()
                )
                database.execSQL("DROP TABLE `council_experts`")
                database.execSQL("ALTER TABLE `council_experts_new` RENAME TO `council_experts`")

                val synthesizer = CouncilExpertDefaults.Synthesizer.toEntity().copy(selectedForCouncil = false)
                database.execSQL(
                    """
                    INSERT OR IGNORE INTO `council_experts` (
                        `expertId`,
                        `role`,
                        `name`,
                        `description`,
                        `promptPersona`,
                        `perspective`,
                        `providerId`,
                        `expertKind`,
                        `enabled`,
                        `selectedForCouncil`,
                        `sortOrder`,
                        `isSystemPreset`,
                        `createdAt`,
                        `updatedAt`
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    arrayOf(
                        synthesizer.expertId,
                        synthesizer.legacyRole,
                        synthesizer.name,
                        synthesizer.description,
                        synthesizer.promptPersona,
                        synthesizer.perspective,
                        synthesizer.providerId,
                        synthesizer.expertKind.name,
                        if (synthesizer.enabled) 1 else 0,
                        if (synthesizer.selectedForCouncil) 1 else 0,
                        synthesizer.sortOrder,
                        if (synthesizer.isSystemPreset) 1 else 0,
                        synthesizer.createdAt,
                        synthesizer.updatedAt
                    )
                )
            }
        }

        private val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `council_experts_new` (
                        `expertId` TEXT NOT NULL,
                        `role` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `promptPersona` TEXT NOT NULL,
                        `perspective` TEXT NOT NULL,
                        `providerId` TEXT NOT NULL,
                        `expertKind` TEXT NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `selectedForCouncil` INTEGER NOT NULL,
                        `sortOrder` INTEGER NOT NULL,
                        `isSystemPreset` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`expertId`)
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    INSERT OR REPLACE INTO `council_experts_new` (
                        `expertId`,
                        `role`,
                        `name`,
                        `description`,
                        `promptPersona`,
                        `perspective`,
                        `providerId`,
                        `expertKind`,
                        `enabled`,
                        `selectedForCouncil`,
                        `sortOrder`,
                        `isSystemPreset`,
                        `createdAt`,
                        `updatedAt`
                    )
                    SELECT
                        CASE
                            WHEN `expertId` = 'Observer' THEN 'preset_observer'
                            WHEN `expertId` = 'Delivery' THEN 'preset_delivery'
                            WHEN `expertId` = 'Psychology' THEN 'preset_psychology'
                            WHEN `expertId` = 'Risk' THEN 'preset_risk'
                            WHEN `expertId` = 'Strategy' THEN 'preset_strategy'
                            WHEN `expertId` = 'Synthesizer' THEN 'preset_synthesizer'
                            ELSE `expertId`
                        END AS `expertId`,
                        `role`,
                        `name`,
                        `description`,
                        `promptPersona`,
                        `perspective`,
                        `providerId`,
                        `expertKind`,
                        `enabled`,
                        `selectedForCouncil`,
                        `sortOrder`,
                        `isSystemPreset`,
                        `createdAt`,
                        `updatedAt`
                    FROM `council_experts`
                    ORDER BY
                        `updatedAt` ASC,
                        CASE
                            WHEN `expertId` IN ('Observer', 'Delivery', 'Psychology', 'Risk', 'Strategy', 'Synthesizer') THEN 0
                            ELSE 1
                        END ASC
                    """.trimIndent()
                )
                database.execSQL("DROP TABLE `council_experts`")
                database.execSQL("ALTER TABLE `council_experts_new` RENAME TO `council_experts`")
            }
        }

        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `video_stream_settings_new` (
                        `id` INTEGER NOT NULL,
                        `ipAddress` TEXT NOT NULL,
                        `port` INTEGER NOT NULL,
                        `resolution` TEXT NOT NULL,
                        `quality` INTEGER NOT NULL,
                        `brightness` INTEGER NOT NULL,
                        `contrast` INTEGER NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `ledControlEnabled` INTEGER NOT NULL,
                        `ledAutoLightEnabled` INTEGER NOT NULL,
                        `ledTargetBrightness` INTEGER NOT NULL,
                        `changeDetectionEnabled` INTEGER NOT NULL,
                        `changeThresholdPercent` INTEGER NOT NULL,
                        `notificationCooldownSeconds` INTEGER NOT NULL,
                        `videoAnalysisStreamingEnabled` INTEGER NOT NULL,
                        `deviceProfile` TEXT NOT NULL,
                        `preferredWifiSsid` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    INSERT INTO `video_stream_settings_new` (
                        `id`,
                        `ipAddress`,
                        `port`,
                        `resolution`,
                        `quality`,
                        `brightness`,
                        `contrast`,
                        `enabled`,
                        `ledControlEnabled`,
                        `ledAutoLightEnabled`,
                        `ledTargetBrightness`,
                        `changeDetectionEnabled`,
                        `changeThresholdPercent`,
                        `notificationCooldownSeconds`,
                        `videoAnalysisStreamingEnabled`,
                        `deviceProfile`,
                        `preferredWifiSsid`
                    )
                    SELECT
                        `id`,
                        `ipAddress`,
                        `port`,
                        `resolution`,
                        `quality`,
                        `brightness`,
                        `contrast`,
                        `enabled`,
                        `ledControlEnabled`,
                        `ledAutoLightEnabled`,
                        `ledTargetBrightness`,
                        `changeDetectionEnabled`,
                        `changeThresholdPercent`,
                        `notificationCooldownSeconds`,
                        `videoAnalysisStreamingEnabled`,
                        `deviceProfile`,
                        `preferredWifiSsid`
                    FROM `video_stream_settings`
                    """.trimIndent()
                )
                database.execSQL("DROP TABLE `video_stream_settings`")
                database.execSQL("ALTER TABLE `video_stream_settings_new` RENAME TO `video_stream_settings`")
            }
        }

        private val MIGRATION_31_32 = object : Migration(31, 32) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // New knowledge table
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `council_knowledge` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `category` TEXT NOT NULL,
                        `sceneType` TEXT NOT NULL DEFAULT 'all',
                        `content` TEXT NOT NULL,
                        `source` TEXT NOT NULL DEFAULT '',
                        `relevance` REAL NOT NULL DEFAULT 1.0,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                // Clear old expert data — will be re-seeded on open with new agent definitions
                database.execSQL("DELETE FROM `council_experts`")
            }
        }

        private val MIGRATION_32_33 = object : Migration(32, 33) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `council_templates` ADD COLUMN `speakerRole` TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE `council_templates` ADD COLUMN `targetRole` TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE `council_templates` ADD COLUMN `background` TEXT NOT NULL DEFAULT ''")
                // Clear default templates so they get re-seeded with new role data
                database.execSQL("DELETE FROM `council_templates` WHERE `isDefault` = 1")
            }
        }

        private val MIGRATION_33_34 = object : Migration(33, 34) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `council_knowledge` ADD COLUMN `expertId` TEXT NOT NULL DEFAULT ''")
                // Clear old low-quality knowledge entries
                database.execSQL("DELETE FROM `council_knowledge`")
            }
        }

        private val MIGRATION_34_35 = object : Migration(34, 35) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `blackboard_days` (
                        `date` TEXT NOT NULL PRIMARY KEY,
                        `sceneMemory` TEXT NOT NULL DEFAULT '',
                        `entityMemory` TEXT NOT NULL DEFAULT '',
                        `actionSummary` TEXT NOT NULL DEFAULT '',
                        `coreMemoryA` TEXT NOT NULL DEFAULT '',
                        `latestMemoryB` TEXT NOT NULL DEFAULT '',
                        `dailyDigest` TEXT NOT NULL DEFAULT '',
                        `totalEntries` INTEGER NOT NULL DEFAULT 0,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `blackboard_entries` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `dayDate` TEXT NOT NULL,
                        `segmentIndex` INTEGER NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `text` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        FOREIGN KEY(`dayDate`) REFERENCES `blackboard_days`(`date`) ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_blackboard_entries_dayDate` ON `blackboard_entries` (`dayDate`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_blackboard_entries_timestamp` ON `blackboard_entries` (`timestamp`)")
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `portrait_dimensions` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `dimensionKey` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `content` TEXT NOT NULL DEFAULT '',
                        `confidence` REAL NOT NULL DEFAULT 0,
                        `observationDays` INTEGER NOT NULL DEFAULT 0,
                        `lastSourceDate` TEXT NOT NULL DEFAULT '',
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_portrait_dimensions_dimensionKey` ON `portrait_dimensions` (`dimensionKey`)")
            }
        }

        private val MIGRATION_35_36 = object : Migration(35, 36) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `blackboard_inferences` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `dayDate` TEXT NOT NULL,
                        `dimensionKey` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `confidence` TEXT NOT NULL,
                        `basis` TEXT NOT NULL DEFAULT '',
                        `createdAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_blackboard_inferences_dayDate` ON `blackboard_inferences` (`dayDate`)")
            }
        }

        private val MIGRATION_36_37 = object : Migration(36, 37) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `behavior_claims` (
                        `claimId` TEXT NOT NULL,
                        `dimensionKey` TEXT NOT NULL,
                        `claimText` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `confidenceScore` REAL NOT NULL,
                        `evidenceSummary` TEXT NOT NULL,
                        `evidenceCount` INTEGER NOT NULL,
                        `firstObservedAt` INTEGER NOT NULL,
                        `lastObservedAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`claimId`)
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_behavior_claims_dimensionKey` ON `behavior_claims` (`dimensionKey`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_behavior_claims_status` ON `behavior_claims` (`status`)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_behavior_claims_dimensionKey_claimText` ON `behavior_claims` (`dimensionKey`, `claimText`)")
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `observation_goals` (
                        `goalId` TEXT NOT NULL,
                        `dimensionKey` TEXT NOT NULL,
                        `question` TEXT NOT NULL,
                        `priority` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `resolutionNote` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`goalId`)
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_observation_goals_dimensionKey` ON `observation_goals` (`dimensionKey`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_observation_goals_status` ON `observation_goals` (`status`)")
            }
        }

        private val MIGRATION_37_38 = object : Migration(37, 38) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `blackboard_observation_items` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `entryId` INTEGER NOT NULL,
                        `dayDate` TEXT NOT NULL,
                        `segmentIndex` INTEGER NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `category` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `dimensionHint` TEXT NOT NULL DEFAULT '',
                        `createdAt` INTEGER NOT NULL,
                        FOREIGN KEY(`entryId`) REFERENCES `blackboard_entries`(`id`) ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_blackboard_observation_items_entryId` ON `blackboard_observation_items` (`entryId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_blackboard_observation_items_dayDate` ON `blackboard_observation_items` (`dayDate`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_blackboard_observation_items_category` ON `blackboard_observation_items` (`category`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_blackboard_observation_items_timestamp` ON `blackboard_observation_items` (`timestamp`)")
            }
        }

        private val MIGRATION_38_39 = object : Migration(38, 39) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `scene_profiles` (
                        `sceneId` TEXT NOT NULL,
                        `label` TEXT NOT NULL,
                        `summary` TEXT NOT NULL,
                        `anchorObjects` TEXT NOT NULL,
                        `layoutHints` TEXT NOT NULL,
                        `stableEntities` TEXT NOT NULL,
                        `usageCount` INTEGER NOT NULL,
                        `lastVerifiedAt` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`sceneId`)
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_scene_profiles_lastVerifiedAt` ON `scene_profiles` (`lastVerifiedAt`)")
            }
        }

        private val MIGRATION_39_40 = object : Migration(39, 40) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `behavior_reasoning_logs` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `dayDate` TEXT NOT NULL,
                        `dimensionKey` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `confidence` TEXT NOT NULL,
                        `basis` TEXT NOT NULL DEFAULT '',
                        `createdAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_behavior_reasoning_logs_dayDate` ON `behavior_reasoning_logs` (`dayDate`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_behavior_reasoning_logs_dimensionKey` ON `behavior_reasoning_logs` (`dimensionKey`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_behavior_reasoning_logs_createdAt` ON `behavior_reasoning_logs` (`createdAt`)"
                )
                database.execSQL("""
                    INSERT INTO `behavior_reasoning_logs` (
                        `dayDate`,
                        `dimensionKey`,
                        `content`,
                        `confidence`,
                        `basis`,
                        `createdAt`
                    )
                    SELECT
                        `dayDate`,
                        `dimensionKey`,
                        `content`,
                        `confidence`,
                        `basis`,
                        `createdAt`
                    FROM `blackboard_inferences`
                """.trimIndent())
                database.execSQL("DROP TABLE IF EXISTS `blackboard_inferences`")
            }
        }

        private val MIGRATION_40_41 = object : Migration(40, 41) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `behavior_reasoning_logs` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `dayDate` TEXT NOT NULL,
                        `dimensionKey` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `confidence` TEXT NOT NULL,
                        `basis` TEXT NOT NULL DEFAULT '',
                        `createdAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_behavior_reasoning_logs_dayDate` ON `behavior_reasoning_logs` (`dayDate`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_behavior_reasoning_logs_dimensionKey` ON `behavior_reasoning_logs` (`dimensionKey`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_behavior_reasoning_logs_createdAt` ON `behavior_reasoning_logs` (`createdAt`)"
                )
                database.execSQL("DROP TABLE IF EXISTS `blackboard_inferences`")
            }
        }

        private val MIGRATION_41_42 = object : Migration(41, 42) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `behavior_claims` ADD COLUMN `sceneId` TEXT DEFAULT NULL"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_behavior_claims_sceneId` ON `behavior_claims` (`sceneId`)"
                )
                database.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_behavior_claims_sceneId_dimensionKey_claimText`
                    ON `behavior_claims` (`sceneId`, `dimensionKey`, `claimText`)
                    """.trimIndent()
                )
                database.execSQL("DROP INDEX IF EXISTS `index_behavior_claims_dimensionKey_claimText`")

                database.execSQL(
                    "ALTER TABLE `observation_goals` ADD COLUMN `sceneId` TEXT DEFAULT NULL"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_observation_goals_sceneId` ON `observation_goals` (`sceneId`)"
                )

                database.execSQL(
                    "ALTER TABLE `behavior_reasoning_logs` ADD COLUMN `sceneId` TEXT DEFAULT NULL"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_behavior_reasoning_logs_sceneId` ON `behavior_reasoning_logs` (`sceneId`)"
                )

                database.execSQL(
                    "ALTER TABLE `scene_profiles` ADD COLUMN `userLabel` TEXT DEFAULT NULL"
                )
            }
        }

        private val MIGRATION_42_43 = object : Migration(42, 43) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `scene_profiles` ADD COLUMN `placeClusterId` TEXT NOT NULL DEFAULT ''"
                )
                database.execSQL(
                    "ALTER TABLE `scene_profiles` ADD COLUMN `placeType` TEXT NOT NULL DEFAULT ''"
                )
                database.execSQL(
                    "ALTER TABLE `scene_profiles` ADD COLUMN `spaceType` TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        private val MIGRATION_43_44 = object : Migration(43, 44) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    DELETE FROM `behavior_claims`
                    WHERE rowid NOT IN (
                        SELECT MIN(rowid)
                        FROM `behavior_claims`
                        GROUP BY COALESCE(`sceneId`, '__universal__'), `dimensionKey`, `claimText`
                    )
                    """.trimIndent()
                )
                database.execSQL("DROP INDEX IF EXISTS `index_behavior_claims_sceneId_dimensionKey_claimText`")
                database.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_behavior_claims_sceneId_dimensionKey_claimText`
                    ON `behavior_claims` (`sceneId`, `dimensionKey`, `claimText`)
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_44_45 = object : Migration(44, 45) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `video_stream_settings` ADD COLUMN `deviceId` TEXT NOT NULL DEFAULT ''"
                )
                database.execSQL(
                    "ALTER TABLE `video_stream_settings` ADD COLUMN `mdnsUrl` TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        private val MIGRATION_45_46 = object : Migration(45, 46) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `video_process_tasks` ADD COLUMN `recordingScenario` TEXT NOT NULL DEFAULT 'general'"
                )
                database.execSQL(
                    "ALTER TABLE `video_process_tasks` ADD COLUMN `speechInputEnabled` INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE `video_process_runs` ADD COLUMN `recordingScenario` TEXT NOT NULL DEFAULT 'general'"
                )
                database.execSQL(
                    "ALTER TABLE `video_process_runs` ADD COLUMN `speechInputEnabled` INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE `video_process_runs` ADD COLUMN `structuredNoteJson` TEXT NOT NULL DEFAULT ''"
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `video_speech_transcripts` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `runId` INTEGER NOT NULL,
                        `segmentIndex` INTEGER,
                        `timestamp` INTEGER NOT NULL,
                        `displayTimestamp` TEXT NOT NULL,
                        `text` TEXT NOT NULL,
                        `isFinal` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        FOREIGN KEY(`runId`) REFERENCES `video_process_runs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_video_speech_transcripts_runId` ON `video_speech_transcripts` (`runId`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_video_speech_transcripts_segmentIndex` ON `video_speech_transcripts` (`segmentIndex`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_video_speech_transcripts_timestamp` ON `video_speech_transcripts` (`timestamp`)"
                )
                database.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_video_speech_transcripts_runId_timestamp_text`
                    ON `video_speech_transcripts` (`runId`, `timestamp`, `text`)
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_46_47 = object : Migration(46, 47) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `video_process_runs` ADD COLUMN `fullMediaPath` TEXT"
                )
                database.execSQL(
                    "ALTER TABLE `video_process_runs` ADD COLUMN `fullMediaDurationMs` INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE `video_process_runs` ADD COLUMN `fullMediaHasAudio` INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE `video_process_runs` ADD COLUMN `fullMediaVideoSource` TEXT NOT NULL DEFAULT ''"
                )
                database.execSQL(
                    "ALTER TABLE `video_segment_runs` ADD COLUMN `mediaStartMs` INTEGER"
                )
                database.execSQL(
                    "ALTER TABLE `video_segment_runs` ADD COLUMN `mediaEndMs` INTEGER"
                )
            }
        }

        private val MIGRATION_47_48 = object : Migration(47, 48) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `video_process_runs` ADD COLUMN `degradedReason` TEXT"
                )
            }
        }

        private val MIGRATION_48_49 = object : Migration(48, 49) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `video_process_runs` ADD COLUMN `markdownNote` TEXT NOT NULL DEFAULT ''"
                )
                database.execSQL(
                    "ALTER TABLE `video_process_runs` ADD COLUMN `audioEnhancementInfo` TEXT NOT NULL DEFAULT ''"
                )
                database.execSQL(
                    "ALTER TABLE `video_segment_runs` ADD COLUMN `evidenceJson` TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        private val MIGRATION_49_50 = object : Migration(49, 50) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `video_audio_assets` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `runId` INTEGER NOT NULL,
                        `segmentRunId` INTEGER,
                        `segmentIndex` INTEGER,
                        `assetType` TEXT NOT NULL,
                        `localFilePath` TEXT NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `sampleRate` INTEGER,
                        `channelCount` INTEGER,
                        `codecMime` TEXT NOT NULL,
                        `sourceVideoPath` TEXT,
                        `diagnosticsJson` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        FOREIGN KEY(`runId`) REFERENCES `video_process_runs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`segmentRunId`) REFERENCES `video_segment_runs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_video_audio_assets_runId` ON `video_audio_assets` (`runId`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_video_audio_assets_segmentRunId` ON `video_audio_assets` (`segmentRunId`)"
                )
                database.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_video_audio_assets_runId_assetType_segmentIndex`
                    ON `video_audio_assets` (`runId`, `assetType`, `segmentIndex`)
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_50_51 = object : Migration(50, 51) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE video_process_runs ADD COLUMN continuousAudioPath TEXT")
                database.execSQL("ALTER TABLE video_process_runs ADD COLUMN continuousAudioDurationMs INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE video_process_runs ADD COLUMN continuousAudioStartedAt INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE video_process_runs ADD COLUMN outlineMarkdown TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE video_process_runs ADD COLUMN outlineGeneratedAt INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE video_process_runs ADD COLUMN reportVersion INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_51_52 = object : Migration(51, 52) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `video_remote_file_bindings` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `runId` INTEGER NOT NULL,
                        `segmentRunId` INTEGER,
                        `assetKind` TEXT NOT NULL,
                        `localPath` TEXT NOT NULL,
                        `lengthBytes` INTEGER NOT NULL,
                        `lastModified` INTEGER NOT NULL,
                        `mediaType` TEXT NOT NULL,
                        `arkFileId` TEXT,
                        `status` TEXT NOT NULL,
                        `uploadAttemptCount` INTEGER NOT NULL,
                        `lastCheckedAt` INTEGER NOT NULL,
                        `diagnosticsJson` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        FOREIGN KEY(`runId`) REFERENCES `video_process_runs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`segmentRunId`) REFERENCES `video_segment_runs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_video_remote_file_bindings_runId` ON `video_remote_file_bindings` (`runId`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_video_remote_file_bindings_segmentRunId` ON `video_remote_file_bindings` (`segmentRunId`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_video_remote_file_bindings_arkFileId` ON `video_remote_file_bindings` (`arkFileId`)"
                )
                database.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_video_remote_file_bindings_runId_assetKind_localPath`
                    ON `video_remote_file_bindings` (`runId`, `assetKind`, `localPath`)
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_52_53 = object : Migration(52, 53) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `video_segment_runs` ADD COLUMN `durationMs` INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE `video_segment_runs` ADD COLUMN `wallClockStartMs` INTEGER"
                )
                database.execSQL(
                    "ALTER TABLE `video_segment_runs` ADD COLUMN `wallClockEndMs` INTEGER"
                )
                database.execSQL(
                    "ALTER TABLE `video_segment_runs` ADD COLUMN `interrupted` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        private val MIGRATION_53_54 = object : Migration(53, 54) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `video_process_runs` ADD COLUMN `mergedSegmentCountActual` INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE `video_process_runs` ADD COLUMN `segmentsMissingMergedAnalysisAsset` INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE `video_process_runs` ADD COLUMN `audioOutlineAvailable` INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE `video_process_runs` ADD COLUMN `videoRefinementApplied` INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE `video_process_runs` ADD COLUMN `videoRefinementInputMode` TEXT NOT NULL DEFAULT ''"
                )
                database.execSQL(
                    "ALTER TABLE `video_process_runs` ADD COLUMN `reportPipelineStagesJson` TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        private val MIGRATION_54_55 = object : Migration(54, 55) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `pose_video_sessions` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `scenario` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `sourceVideoPath` TEXT NOT NULL,
                        `sourceVideoDurationMs` INTEGER NOT NULL DEFAULT 0,
                        `sourceVideoWidth` INTEGER NOT NULL DEFAULT 0,
                        `sourceVideoHeight` INTEGER NOT NULL DEFAULT 0,
                        `sourceFps` INTEGER NOT NULL DEFAULT 30,
                        `frameCount` INTEGER NOT NULL DEFAULT 0,
                        `landmarkCount` INTEGER NOT NULL DEFAULT 33,
                        `rawPoseFilePath` TEXT NOT NULL DEFAULT '',
                        `smoothPoseFilePath` TEXT NOT NULL DEFAULT '',
                        `processingStatus` TEXT NOT NULL DEFAULT 'pending',
                        `processingProgress` REAL NOT NULL DEFAULT 0,
                        `processingError` TEXT,
                        `thumbnailPath` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_pose_video_sessions_scenario` ON `pose_video_sessions` (`scenario`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_pose_video_sessions_processingStatus` ON `pose_video_sessions` (`processingStatus`)")
            }
        }

        private val MIGRATION_55_56 = object : Migration(55, 56) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `pose_video_sessions` ADD COLUMN `clipStartMs` INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE `pose_video_sessions` ADD COLUMN `clipEndMs` INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_56_57 = object : Migration(56, 57) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `pose_video_sessions` ADD COLUMN `beatFilePath` TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE `pose_video_sessions` ADD COLUMN `audioFileId` TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_57_58 = object : Migration(57, 58) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `video_process_runs` ADD COLUMN `aiTraceId` TEXT NOT NULL DEFAULT ''")
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `video_ai_trace_events` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `traceId` TEXT NOT NULL,
                        `runId` INTEGER,
                        `taskId` INTEGER,
                        `node` TEXT NOT NULL,
                        `phase` TEXT NOT NULL,
                        `segmentIndex` INTEGER,
                        `chunkIndex` INTEGER,
                        `model` TEXT NOT NULL,
                        `requestKind` TEXT NOT NULL,
                        `promptText` TEXT NOT NULL,
                        `requestPayloadJson` TEXT NOT NULL,
                        `rawResponseText` TEXT NOT NULL,
                        `parsedSummary` TEXT NOT NULL,
                        `parsedJson` TEXT NOT NULL,
                        `errorMessage` TEXT NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `sequence` INTEGER NOT NULL,
                        `contentHash` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_video_ai_trace_events_traceId` ON `video_ai_trace_events` (`traceId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_video_ai_trace_events_runId` ON `video_ai_trace_events` (`runId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_video_ai_trace_events_taskId` ON `video_ai_trace_events` (`taskId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_video_ai_trace_events_node` ON `video_ai_trace_events` (`node`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_video_ai_trace_events_createdAt` ON `video_ai_trace_events` (`createdAt`)")
            }
        }

        private val MIGRATION_58_59 = object : Migration(58, 59) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `video_speech_transcripts` ADD COLUMN `globalStartMs` INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE `video_speech_transcripts` ADD COLUMN `globalEndMs` INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE `video_speech_transcripts` ADD COLUMN `definite` INTEGER NOT NULL DEFAULT 1")
                database.execSQL("ALTER TABLE `video_speech_transcripts` ADD COLUMN `wordsJson` TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE `video_speech_transcripts` ADD COLUMN `source` TEXT NOT NULL DEFAULT 'legacy'")
                database.execSQL("ALTER TABLE `video_speech_transcripts` ADD COLUMN `asrLogId` TEXT NOT NULL DEFAULT ''")
                database.execSQL("UPDATE `video_speech_transcripts` SET `globalStartMs` = `timestamp`, `globalEndMs` = `timestamp`, `definite` = `isFinal`")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_video_speech_transcripts_globalStartMs` ON `video_speech_transcripts` (`globalStartMs`)")
            }
        }

        private val MIGRATION_59_60 = object : Migration(59, 60) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `classroom_transcript_consumptions` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `runId` INTEGER NOT NULL,
                        `transcriptId` INTEGER NOT NULL,
                        `selectionOrder` INTEGER NOT NULL,
                        `weightLevel` TEXT NOT NULL,
                        `isSelected` INTEGER NOT NULL,
                        `isAnswered` INTEGER NOT NULL,
                        `questionType` TEXT NOT NULL,
                        `questionText` TEXT NOT NULL,
                        `answerText` TEXT NOT NULL,
                        `contextStartMs` INTEGER NOT NULL,
                        `contextEndMs` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        FOREIGN KEY(`runId`) REFERENCES `video_process_runs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`transcriptId`) REFERENCES `video_speech_transcripts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_classroom_transcript_consumptions_runId` ON `classroom_transcript_consumptions` (`runId`)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_classroom_transcript_consumptions_transcriptId` ON `classroom_transcript_consumptions` (`transcriptId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_classroom_transcript_consumptions_selectionOrder` ON `classroom_transcript_consumptions` (`selectionOrder`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_classroom_transcript_consumptions_isAnswered` ON `classroom_transcript_consumptions` (`isAnswered`)")
            }
        }

        private val MIGRATION_60_61 = object : Migration(60, 61) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `classroom_transcript_consumptions` ADD COLUMN `visualFrameTimestampMs` INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE `classroom_transcript_consumptions` ADD COLUMN `visualFramePath` TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE `classroom_transcript_consumptions` ADD COLUMN `visualFrameStatus` TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_61_62 = object : Migration(61, 62) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `video_stream_settings` ADD COLUMN `rotationDegrees` INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE `video_stream_settings` ADD COLUMN `mirrorHorizontally` INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_62_63 = object : Migration(62, 63) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `video_process_runs` ADD COLUMN `classroomKnowledgeTreeJson` TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE `video_process_runs` ADD COLUMN `classroomKnowledgeFrameRefsJson` TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE `video_process_runs` ADD COLUMN `classroomKnowledgeTreeStatus` TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE `video_process_runs` ADD COLUMN `classroomKnowledgeTreeUpdatedAt` INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_63_64 = object : Migration(63, 64) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `classroom_note_followups` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `runId` INTEGER NOT NULL,
                        `question` TEXT NOT NULL,
                        `answer` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `contextStage` TEXT NOT NULL,
                        `sourceRefsJson` TEXT NOT NULL,
                        `conversationContextIdsJson` TEXT NOT NULL,
                        `rawResponse` TEXT NOT NULL,
                        `errorMessage` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        FOREIGN KEY(`runId`) REFERENCES `video_process_runs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_classroom_note_followups_runId` ON `classroom_note_followups` (`runId`)"
                )
            }
        }

        private val MIGRATION_64_65 = object : Migration(64, 65) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `fitness_user_profiles` (
                        `profileId` TEXT NOT NULL,
                        `goalType` TEXT NOT NULL,
                        `previousAttempt` TEXT NOT NULL,
                        `targetPartsJson` TEXT NOT NULL,
                        `targetWeightKg` REAL,
                        `gender` TEXT NOT NULL,
                        `age` INTEGER NOT NULL,
                        `heightCm` INTEGER NOT NULL,
                        `currentWeightKg` REAL NOT NULL,
                        `currentBodyType` TEXT NOT NULL,
                        `targetBodyType` TEXT NOT NULL,
                        `injuryPartsJson` TEXT NOT NULL,
                        `sedentaryLevel` TEXT NOT NULL,
                        `sleepQuality` TEXT NOT NULL,
                        `dietHabitsJson` TEXT NOT NULL,
                        `rewardPreference` TEXT NOT NULL,
                        `exerciseFrequency` TEXT NOT NULL,
                        `preferredPlacesJson` TEXT NOT NULL,
                        `gymVisitsPerWeek` INTEGER NOT NULL,
                        `equipmentKnowledge` TEXT NOT NULL,
                        `plankSeconds` INTEGER NOT NULL,
                        `stairFeeling` TEXT NOT NULL,
                        `onboardingStep` INTEGER NOT NULL,
                        `isComplete` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`profileId`)
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `fitness_media_assets` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `profileId` TEXT NOT NULL,
                        `assetType` TEXT NOT NULL,
                        `localPath` TEXT NOT NULL,
                        `mimeType` TEXT NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `width` INTEGER NOT NULL,
                        `height` INTEGER NOT NULL,
                        `notes` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_media_assets_profileId` ON `fitness_media_assets` (`profileId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_media_assets_assetType` ON `fitness_media_assets` (`assetType`)")
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `fitness_strategy_goals` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `profileId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `phaseLabel` TEXT NOT NULL,
                        `startDateEpochDay` INTEGER NOT NULL,
                        `endDateEpochDay` INTEGER NOT NULL,
                        `summary` TEXT NOT NULL,
                        `milestonesJson` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `rawJson` TEXT NOT NULL,
                        `errorMessage` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_strategy_goals_profileId` ON `fitness_strategy_goals` (`profileId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_strategy_goals_status` ON `fitness_strategy_goals` (`status`)")
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `fitness_workout_plans` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `profileId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `objective` TEXT NOT NULL,
                        `plannedDateEpochDay` INTEGER NOT NULL,
                        `estimatedMinutes` INTEGER NOT NULL,
                        `intensityLabel` TEXT NOT NULL,
                        `warmup` TEXT NOT NULL,
                        `cooldown` TEXT NOT NULL,
                        `coachNotes` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `generationStatus` TEXT NOT NULL,
                        `rawJson` TEXT NOT NULL,
                        `errorMessage` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_workout_plans_profileId` ON `fitness_workout_plans` (`profileId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_workout_plans_status` ON `fitness_workout_plans` (`status`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_workout_plans_plannedDateEpochDay` ON `fitness_workout_plans` (`plannedDateEpochDay`)")
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `fitness_workout_exercises` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `planId` INTEGER NOT NULL,
                        `sortOrder` INTEGER NOT NULL,
                        `category` TEXT NOT NULL,
                        `equipment` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `sets` INTEGER NOT NULL,
                        `reps` TEXT NOT NULL,
                        `durationSeconds` INTEGER NOT NULL,
                        `restSeconds` INTEGER NOT NULL,
                        `intensity` TEXT NOT NULL,
                        `notes` TEXT NOT NULL,
                        FOREIGN KEY(`planId`) REFERENCES `fitness_workout_plans`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_workout_exercises_planId` ON `fitness_workout_exercises` (`planId`)")
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `fitness_workout_logs` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `profileId` TEXT NOT NULL,
                        `planId` INTEGER NOT NULL,
                        `completionLevel` TEXT NOT NULL,
                        `fatigueLevel` TEXT NOT NULL,
                        `painSignal` TEXT NOT NULL,
                        `nextIntensityPreference` TEXT NOT NULL,
                        `noteOption` TEXT NOT NULL,
                        `completedAt` INTEGER NOT NULL,
                        FOREIGN KEY(`planId`) REFERENCES `fitness_workout_plans`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_workout_logs_profileId` ON `fitness_workout_logs` (`profileId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_workout_logs_planId` ON `fitness_workout_logs` (`planId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_workout_logs_completedAt` ON `fitness_workout_logs` (`completedAt`)")
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `fitness_agent_runs` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `profileId` TEXT NOT NULL,
                        `agentType` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `promptSummary` TEXT NOT NULL,
                        `rawResponse` TEXT NOT NULL,
                        `parsedJson` TEXT NOT NULL,
                        `errorMessage` TEXT NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_agent_runs_profileId` ON `fitness_agent_runs` (`profileId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_agent_runs_agentType` ON `fitness_agent_runs` (`agentType`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_agent_runs_status` ON `fitness_agent_runs` (`status`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_agent_runs_createdAt` ON `fitness_agent_runs` (`createdAt`)")
            }
        }

        private val MIGRATION_65_66 = object : Migration(65, 66) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `fitness_strategy_specs` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `profileId` TEXT NOT NULL,
                        `strategyVersion` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `isActive` INTEGER NOT NULL,
                        `goalsJson` TEXT NOT NULL,
                        `currentPhaseJson` TEXT NOT NULL,
                        `weeklyBudgetJson` TEXT NOT NULL,
                        `progressionRulesJson` TEXT NOT NULL,
                        `autoregulationRulesJson` TEXT NOT NULL,
                        `hardConstraintsJson` TEXT NOT NULL,
                        `replanTriggersJson` TEXT NOT NULL,
                        `rawJson` TEXT NOT NULL,
                        `errorMessage` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_strategy_specs_profileId` ON `fitness_strategy_specs` (`profileId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_strategy_specs_strategyVersion` ON `fitness_strategy_specs` (`strategyVersion`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_strategy_specs_status` ON `fitness_strategy_specs` (`status`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_strategy_specs_isActive` ON `fitness_strategy_specs` (`isActive`)")
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `fitness_weekly_ledgers` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `profileId` TEXT NOT NULL,
                        `strategyVersion` TEXT NOT NULL,
                        `weekStartEpochDay` INTEGER NOT NULL,
                        `weekIndex` INTEGER NOT NULL,
                        `weeklyBudgetJson` TEXT NOT NULL,
                        `actualsJson` TEXT NOT NULL,
                        `remainingBudgetJson` TEXT NOT NULL,
                        `readinessTrendJson` TEXT NOT NULL,
                        `painTrendJson` TEXT NOT NULL,
                        `replanRequired` INTEGER NOT NULL,
                        `replanReason` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_weekly_ledgers_profileId` ON `fitness_weekly_ledgers` (`profileId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_weekly_ledgers_strategyVersion` ON `fitness_weekly_ledgers` (`strategyVersion`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_weekly_ledgers_weekStartEpochDay` ON `fitness_weekly_ledgers` (`weekStartEpochDay`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_weekly_ledgers_replanRequired` ON `fitness_weekly_ledgers` (`replanRequired`)")
                database.execSQL("ALTER TABLE `fitness_workout_plans` ADD COLUMN `sessionId` TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE `fitness_workout_plans` ADD COLUMN `strategyVersion` TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE `fitness_workout_plans` ADD COLUMN `dailyContextJson` TEXT NOT NULL DEFAULT '{}'")
                database.execSQL("ALTER TABLE `fitness_workout_plans` ADD COLUMN `sessionPlanJson` TEXT NOT NULL DEFAULT '{}'")
                database.execSQL("ALTER TABLE `fitness_workout_plans` ADD COLUMN `expectedBudgetUsageJson` TEXT NOT NULL DEFAULT '{}'")
                database.execSQL("ALTER TABLE `fitness_workout_plans` ADD COLUMN `adjustmentsJson` TEXT NOT NULL DEFAULT '[]'")
                database.execSQL("ALTER TABLE `fitness_workout_plans` ADD COLUMN `stopRulesJson` TEXT NOT NULL DEFAULT '[]'")
                database.execSQL("ALTER TABLE `fitness_workout_exercises` ADD COLUMN `movementPattern` TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE `fitness_workout_exercises` ADD COLUMN `targetMusclesJson` TEXT NOT NULL DEFAULT '[]'")
                database.execSQL("ALTER TABLE `fitness_workout_exercises` ADD COLUMN `warmupSetsJson` TEXT NOT NULL DEFAULT '[]'")
                database.execSQL("ALTER TABLE `fitness_workout_exercises` ADD COLUMN `repRangeMin` INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE `fitness_workout_exercises` ADD COLUMN `repRangeMax` INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE `fitness_workout_exercises` ADD COLUMN `targetRir` REAL NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE `fitness_workout_exercises` ADD COLUMN `restSecondsMin` INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE `fitness_workout_exercises` ADD COLUMN `restSecondsMax` INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE `fitness_workout_exercises` ADD COLUMN `tempo` TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE `fitness_workout_exercises` ADD COLUMN `loadSelectionRule` TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE `fitness_workout_exercises` ADD COLUMN `progressionRule` TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE `fitness_workout_exercises` ADD COLUMN `substitutionsJson` TEXT NOT NULL DEFAULT '[]'")
                database.execSQL("ALTER TABLE `fitness_workout_exercises` ADD COLUMN `stopCondition` TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE `fitness_workout_exercises` ADD COLUMN `priority` TEXT NOT NULL DEFAULT ''")
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `fitness_session_results` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `profileId` TEXT NOT NULL,
                        `planId` INTEGER NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `strategyVersion` TEXT NOT NULL,
                        `completionRate` REAL NOT NULL,
                        `actualDurationMin` INTEGER NOT NULL,
                        `sessionRpe` REAL NOT NULL,
                        `painEventsJson` TEXT NOT NULL,
                        `unexpectedFatigue` INTEGER NOT NULL,
                        `userFeedback` TEXT NOT NULL,
                        `postSessionReadiness` INTEGER NOT NULL,
                        `rawJson` TEXT NOT NULL,
                        `completedAt` INTEGER NOT NULL,
                        FOREIGN KEY(`planId`) REFERENCES `fitness_workout_plans`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_session_results_profileId` ON `fitness_session_results` (`profileId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_session_results_planId` ON `fitness_session_results` (`planId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_session_results_sessionId` ON `fitness_session_results` (`sessionId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_session_results_completedAt` ON `fitness_session_results` (`completedAt`)")
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `fitness_exercise_results` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionResultId` INTEGER NOT NULL,
                        `exerciseId` INTEGER NOT NULL,
                        `actualLoad` TEXT NOT NULL,
                        `actualSets` INTEGER NOT NULL,
                        `actualReps` TEXT NOT NULL,
                        `actualRpe` REAL NOT NULL,
                        `actualRir` REAL NOT NULL,
                        `completionStatus` TEXT NOT NULL,
                        `painScore` INTEGER NOT NULL,
                        `substituted` INTEGER NOT NULL,
                        `unfinishedReason` TEXT NOT NULL,
                        FOREIGN KEY(`sessionResultId`) REFERENCES `fitness_session_results`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`exerciseId`) REFERENCES `fitness_workout_exercises`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_exercise_results_sessionResultId` ON `fitness_exercise_results` (`sessionResultId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_exercise_results_exerciseId` ON `fitness_exercise_results` (`exerciseId`)")
            }
        }

        private val MIGRATION_66_67 = object : Migration(66, 67) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `fitness_realtime_feedback_events` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `profileId` TEXT NOT NULL,
                        `planId` INTEGER NOT NULL,
                        `exerciseId` INTEGER NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `exerciseIntervalId` TEXT NOT NULL,
                        `segmentId` TEXT NOT NULL,
                        `observerId` TEXT NOT NULL,
                        `eventType` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `segmentStartElapsedMs` INTEGER NOT NULL,
                        `segmentEndElapsedMs` INTEGER NOT NULL,
                        `analysisFinishedElapsedMs` INTEGER NOT NULL,
                        `rawObserverJson` TEXT NOT NULL,
                        `rawCoachJson` TEXT NOT NULL,
                        `finalFeedback` TEXT NOT NULL,
                        `discardReason` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_realtime_feedback_events_profileId` ON `fitness_realtime_feedback_events` (`profileId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_realtime_feedback_events_planId` ON `fitness_realtime_feedback_events` (`planId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_realtime_feedback_events_exerciseId` ON `fitness_realtime_feedback_events` (`exerciseId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_realtime_feedback_events_sessionId` ON `fitness_realtime_feedback_events` (`sessionId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_realtime_feedback_events_exerciseIntervalId` ON `fitness_realtime_feedback_events` (`exerciseIntervalId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_realtime_feedback_events_segmentId` ON `fitness_realtime_feedback_events` (`segmentId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_realtime_feedback_events_eventType` ON `fitness_realtime_feedback_events` (`eventType`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_realtime_feedback_events_createdAt` ON `fitness_realtime_feedback_events` (`createdAt`)")
            }
        }

        private val MIGRATION_67_68 = object : Migration(67, 68) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `fitness_realtime_feedback_events` ADD COLUMN `knowledgePackId` TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE `fitness_realtime_feedback_events` ADD COLUMN `knowledgePackTag` TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE `fitness_realtime_feedback_events` ADD COLUMN `exerciseName` TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE `fitness_realtime_feedback_events` ADD COLUMN `exerciseEquipment` TEXT NOT NULL DEFAULT ''")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_realtime_feedback_events_knowledgePackId` ON `fitness_realtime_feedback_events` (`knowledgePackId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_realtime_feedback_events_knowledgePackTag` ON `fitness_realtime_feedback_events` (`knowledgePackTag`)")
            }
        }

        private val MIGRATION_68_69 = object : Migration(68, 69) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `fitness_rep_events` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `profileId` TEXT NOT NULL,
                        `planId` INTEGER NOT NULL,
                        `exerciseId` INTEGER NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `exerciseIntervalId` TEXT NOT NULL,
                        `repIndex` INTEGER NOT NULL,
                        `startElapsedMs` INTEGER NOT NULL,
                        `endElapsedMs` INTEGER NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `activeLandmarksJson` TEXT NOT NULL,
                        `dominantAxis` TEXT NOT NULL,
                        `rangeScore` REAL NOT NULL,
                        `smoothnessScore` REAL NOT NULL,
                        `visibilityScore` REAL NOT NULL,
                        `symmetryScore` REAL NOT NULL,
                        `confidence` REAL NOT NULL,
                        `qualityLabel` TEXT NOT NULL,
                        `rawSignalsJson` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_rep_events_profileId` ON `fitness_rep_events` (`profileId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_rep_events_planId` ON `fitness_rep_events` (`planId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_rep_events_exerciseId` ON `fitness_rep_events` (`exerciseId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_rep_events_sessionId` ON `fitness_rep_events` (`sessionId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_rep_events_exerciseIntervalId` ON `fitness_rep_events` (`exerciseIntervalId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_rep_events_createdAt` ON `fitness_rep_events` (`createdAt`)")
            }
        }

        private val MIGRATION_69_70 = object : Migration(69, 70) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `fitness_exercises` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `displayNameZh` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `body_part` TEXT NOT NULL,
                        `equipment` TEXT NOT NULL,
                        `muscle_group` TEXT NOT NULL,
                        `target` TEXT NOT NULL,
                        `media_id` TEXT NOT NULL,
                        `image` TEXT NOT NULL,
                        `gif_url` TEXT NOT NULL,
                        `attribution` TEXT NOT NULL,
                        `created_at` TEXT NOT NULL,
                        `searchText` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_exercises_category` ON `fitness_exercises` (`category`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_exercises_body_part` ON `fitness_exercises` (`body_part`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_exercises_equipment` ON `fitness_exercises` (`equipment`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_exercises_target` ON `fitness_exercises` (`target`)")
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `fitness_exercise_instructions` (
                        `exerciseId` TEXT NOT NULL,
                        `language` TEXT NOT NULL,
                        `fullText` TEXT NOT NULL,
                        PRIMARY KEY(`exerciseId`, `language`)
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_exercise_instructions_exerciseId` ON `fitness_exercise_instructions` (`exerciseId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_exercise_instructions_language` ON `fitness_exercise_instructions` (`language`)")
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `fitness_exercise_instruction_steps` (
                        `exerciseId` TEXT NOT NULL,
                        `language` TEXT NOT NULL,
                        `stepIndex` INTEGER NOT NULL,
                        `text` TEXT NOT NULL,
                        PRIMARY KEY(`exerciseId`, `language`, `stepIndex`)
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_exercise_instruction_steps_exerciseId` ON `fitness_exercise_instruction_steps` (`exerciseId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_exercise_instruction_steps_language` ON `fitness_exercise_instruction_steps` (`language`)")
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `fitness_exercise_secondary_muscles` (
                        `exerciseId` TEXT NOT NULL,
                        `muscle` TEXT NOT NULL,
                        PRIMARY KEY(`exerciseId`, `muscle`)
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_exercise_secondary_muscles_exerciseId` ON `fitness_exercise_secondary_muscles` (`exerciseId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_exercise_secondary_muscles_muscle` ON `fitness_exercise_secondary_muscles` (`muscle`)")
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `fitness_exercise_library_meta` (
                        `datasetId` TEXT NOT NULL,
                        `sourceHash` TEXT NOT NULL,
                        `exerciseCount` INTEGER NOT NULL,
                        `importedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`datasetId`)
                    )
                    """.trimIndent()
                )
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: buildWatcherDatabase(
                    context = context.applicationContext,
                    migrations = allMigrations(),
                    databaseProvider = { instance }
                ).also { instance = it }
            }
        }

        private fun allMigrations(): Array<Migration> = arrayOf(
            MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15,
            MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19,
            MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23,
            MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27,
            MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31,
            MIGRATION_31_32, MIGRATION_32_33, MIGRATION_33_34, MIGRATION_34_35,
            MIGRATION_35_36, MIGRATION_36_37, MIGRATION_37_38, MIGRATION_38_39,
            MIGRATION_39_40, MIGRATION_40_41, MIGRATION_41_42, MIGRATION_42_43,
            MIGRATION_43_44, MIGRATION_44_45, MIGRATION_45_46, MIGRATION_46_47,
            MIGRATION_47_48, MIGRATION_48_49, MIGRATION_49_50, MIGRATION_50_51,
            MIGRATION_51_52, MIGRATION_52_53, MIGRATION_53_54, MIGRATION_54_55, MIGRATION_55_56,
            MIGRATION_56_57, MIGRATION_57_58, MIGRATION_58_59, MIGRATION_59_60, MIGRATION_60_61,
            MIGRATION_61_62, MIGRATION_62_63, MIGRATION_63_64, MIGRATION_64_65, MIGRATION_65_66,
            MIGRATION_66_67, MIGRATION_67_68, MIGRATION_68_69, MIGRATION_69_70
        )
    }
}
