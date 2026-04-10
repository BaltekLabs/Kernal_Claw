package com.ace.crm.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sms")
data class SmsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val address: String,
    val body: String,
    val timestamp: Long,
    val type: Int
)
