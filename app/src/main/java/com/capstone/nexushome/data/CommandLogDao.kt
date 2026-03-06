package com.capstone.nexushome.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CommandLogDao {

    @Insert
    suspend fun insert(log: CommandLog)

    @Query("SELECT * FROM command_log ORDER BY id DESC")
    fun observeAllLogs(): Flow<List<CommandLog>>

    @Query("SELECT * FROM command_log ORDER BY id DESC")
    suspend fun getAllLogs(): List<CommandLog>

    @Query("DELETE FROM command_log")
    suspend fun clearAll()
}
