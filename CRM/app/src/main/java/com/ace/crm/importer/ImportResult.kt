package com.ace.crm.importer

data class ImportResult(
    val contacts: Int,
    val callLogs: Int,
    val sms: Int,
    val calendarEvents: Int = 0
)
