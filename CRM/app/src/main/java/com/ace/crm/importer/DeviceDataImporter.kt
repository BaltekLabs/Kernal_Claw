package com.ace.crm.importer

import android.content.Context
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import com.ace.crm.data.CallLogEntity
import com.ace.crm.data.ContactEntity
import com.ace.crm.data.CrmDao
import com.ace.crm.data.SmsEntity

class DeviceDataImporter(
    private val dao: CrmDao
) {
    suspend fun importAll(context: Context): ImportResult {
        val contacts = loadContacts(context)
        val callLogs = loadCallLogs(context)
        val sms = loadSms(context)

        if (contacts.isNotEmpty()) dao.insertContacts(contacts)
        if (callLogs.isNotEmpty()) dao.insertCallLogs(callLogs)
        if (sms.isNotEmpty()) dao.insertSms(sms)

        return ImportResult(
            contacts = contacts.size,
            callLogs = callLogs.size,
            sms = sms.size,
            calendarEvents = 0
        )
    }

    private fun loadContacts(context: Context): List<ContactEntity> {
        val resolver = context.contentResolver
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val result = mutableListOf<ContactEntity>()

            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex) ?: "Unknown"
                val number = cursor.getString(numberIndex)
                result += ContactEntity(displayName = name, phoneNumber = number, email = null)
            }
            return result
        }

        return emptyList()
    }

    private fun loadCallLogs(context: Context): List<CallLogEntity> {
        val resolver = context.contentResolver
        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.DURATION,
            CallLog.Calls.DATE,
            CallLog.Calls.TYPE
        )

        resolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            null,
            null,
            CallLog.Calls.DATE + " DESC"
        )?.use { cursor ->
            val numberIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val durationIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
            val dateIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
            val typeIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            val result = mutableListOf<CallLogEntity>()

            while (cursor.moveToNext()) {
                result += CallLogEntity(
                    number = cursor.getString(numberIndex) ?: "",
                    durationSeconds = cursor.getLong(durationIndex),
                    timestamp = cursor.getLong(dateIndex),
                    type = cursor.getInt(typeIndex)
                )
            }
            return result
        }

        return emptyList()
    }

    private fun loadSms(context: Context): List<SmsEntity> {
        val resolver = context.contentResolver
        val projection = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE
        )

        resolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            null,
            null,
            Telephony.Sms.DEFAULT_SORT_ORDER
        )?.use { cursor ->
            val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val typeIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            val result = mutableListOf<SmsEntity>()

            while (cursor.moveToNext()) {
                result += SmsEntity(
                    address = cursor.getString(addressIndex) ?: "",
                    body = cursor.getString(bodyIndex) ?: "",
                    timestamp = cursor.getLong(dateIndex),
                    type = cursor.getInt(typeIndex)
                )
            }
            return result
        }

        return emptyList()
    }
}
