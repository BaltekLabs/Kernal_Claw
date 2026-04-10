package com.ace.crm.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CrmDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContacts(contacts: List<ContactEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCallLogs(callLogs: List<CallLogEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSms(messages: List<SmsEntity>)

    @Query("SELECT COUNT(*) FROM contacts")
    suspend fun contactCount(): Int

    @Query("SELECT COUNT(*) FROM call_logs")
    suspend fun callLogCount(): Int

    @Query("SELECT COUNT(*) FROM sms")
    suspend fun smsCount(): Int

    @Query("SELECT * FROM contacts ORDER BY displayName ASC")
    suspend fun getAllContacts(): List<ContactEntity>
}
