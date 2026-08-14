package com.example.watcher.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.watcher.data.model.ClassroomTranscriptConsumptionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ClassroomTranscriptConsumptionDao {
    @Query("SELECT * FROM classroom_transcript_consumptions WHERE runId = :runId ORDER BY selectionOrder ASC, updatedAt ASC")
    fun observeForRun(runId: Long): Flow<List<ClassroomTranscriptConsumptionEntity>>

    @Query("SELECT * FROM classroom_transcript_consumptions WHERE runId = :runId")
    suspend fun getForRun(runId: Long): List<ClassroomTranscriptConsumptionEntity>

    @Query("SELECT * FROM classroom_transcript_consumptions WHERE runId = :runId AND transcriptId = :transcriptId LIMIT 1")
    suspend fun getForTranscript(runId: Long, transcriptId: Long): ClassroomTranscriptConsumptionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ClassroomTranscriptConsumptionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<ClassroomTranscriptConsumptionEntity>)

    @Query("UPDATE classroom_transcript_consumptions SET isSelected = 0, selectionOrder = 0, weightLevel = '', updatedAt = :updatedAt WHERE runId = :runId AND isAnswered = 0")
    suspend fun clearOpenSelections(runId: Long, updatedAt: Long)
}
