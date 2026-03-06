package com.capstone.nexushome.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "command_log")
data class CommandLog(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val timestamp: String,
    val action: String
)
