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
import com.example.watcher.data.model.VideoProcessRun
import com.example.watcher.data.model.VideoProcessTask
import com.example.watcher.data.model.VideoSegmentRun
import com.example.watcher.data.model.VideoStreamSettings
import com.example.watcher.data.model.AiAudienceEntity
import com.example.watcher.data.model.AiAudienceMessageEntity
import com.example.watcher.data.model.BlackboardDay
import com.example.watcher.data.model.BlackboardEntry
import com.example.watcher.data.model.BlackboardObservationItem
import com.example.watcher.data.model.BehaviorClaim
import com.example.watcher.data.model.BehaviorReasoningLog
import com.example.watcher.data.model.CouncilExpertDefaults
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
        SceneProfile::class
    ],
    version = 45,
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
            MIGRATION_43_44, MIGRATION_44_45
        )
    }
}
