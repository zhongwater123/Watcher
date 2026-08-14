package com.example.watcher.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.watcher.data.model.ClassroomNoteFollowupEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ClassroomNoteFollowupDao {
    @Query("SELECT * FROM classroom_note_followups WHERE runId = :runId ORDER BY createdAt ASC, id ASC")
    fun observeForRun(runId: Long): Flow<List<ClassroomNoteFollowupEntity>>

    @Query("SELECT * FROM classroom_note_followups WHERE runId = :runId ORDER BY createdAt ASC, id ASC")
    suspend fun getForRun(runId: Long): List<ClassroomNoteFollowupEntity>

    @Query("SELECT * FROM classroom_note_followups WHERE id = :id")
    suspend fun getById(id: Long): ClassroomNoteFollowupEntity?

    @Query(
        """
        SELECT * FROM (
            SELECT * FROM classroom_note_followups
            WHERE runId = :runId AND status = 'completed'
            ORDER BY createdAt DESC, id DESC
            LIMIT :limit
        )
        ORDER BY createdAt ASC, id ASC
        """
    )
    suspend fun getRecentCompletedForRun(runId: Long, limit: Int): List<ClassroomNoteFollowupEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ClassroomNoteFollowupEntity): Long

    @Update
    suspend fun update(entity: ClassroomNoteFollowupEntity)

    @Query("DELETE FROM classroom_note_followups WHERE runId = :runId")
    suspend fun deleteByRunId(runId: Long)

    @Query("DELETE FROM classroom_note_followups WHERE id = :id")
    suspend fun deleteById(id: Long)
}
