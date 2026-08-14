package com.example.watcher.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.watcher.data.model.VideoSpeechTranscriptEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoSpeechTranscriptDao {
    @Query("SELECT * FROM video_speech_transcripts WHERE runId = :runId ORDER BY timestamp ASC, id ASC")
    fun observeForRun(runId: Long): Flow<List<VideoSpeechTranscriptEntity>>

    @Query(
        """
        SELECT * FROM (
            SELECT * FROM video_speech_transcripts
            WHERE runId = :runId
            ORDER BY timestamp DESC, id DESC
            LIMIT :limit
        )
        ORDER BY timestamp ASC, id ASC
        """
    )
    fun observeRecentForRun(runId: Long, limit: Int): Flow<List<VideoSpeechTranscriptEntity>>

    @Query("SELECT COUNT(*) FROM video_speech_transcripts WHERE runId = :runId")
    fun observeCountForRun(runId: Long): Flow<Int>

    @Query("SELECT * FROM video_speech_transcripts WHERE runId = :runId ORDER BY timestamp ASC, id ASC")
    suspend fun getForRun(runId: Long): List<VideoSpeechTranscriptEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(transcripts: List<VideoSpeechTranscriptEntity>)
}
