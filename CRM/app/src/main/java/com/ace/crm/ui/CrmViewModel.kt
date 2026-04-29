package com.ace.crm.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ace.crm.data.AppDatabase
import com.ace.crm.export.VcfExporter
import com.ace.crm.importer.DeviceDataImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CrmViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(CrmUiState())
    val uiState: StateFlow<CrmUiState> = _uiState.asStateFlow()

    fun refreshSummary(context: Context) {
        viewModelScope.launch {
            val summary = withContext(Dispatchers.IO) {
                val dao = AppDatabase.get(context).crmDao()
                Triple(dao.contactCount(), dao.callLogCount(), dao.smsCount())
            }

            _uiState.update {
                it.copy(
                    contactCount = summary.first,
                    callLogCount = summary.second,
                    smsCount = summary.third,
                    message = null
                )
            }
        }
    }

    fun importDeviceData(context: Context) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, message = null) }

            val result = withContext(Dispatchers.IO) {
                val dao = AppDatabase.get(context).crmDao()
                val importResult = DeviceDataImporter(dao).importAll(context)
                val summary = Triple(dao.contactCount(), dao.callLogCount(), dao.smsCount())
                Pair(importResult, summary)
            }

            _uiState.update {
                it.copy(
                    contactCount = result.second.first,
                    callLogCount = result.second.second,
                    smsCount = result.second.third,
                    isLoading = false,
                    message = "Imported contacts=${result.first.contacts}, calls=${result.first.callLogs}, sms=${result.first.sms}, calendar=${result.first.calendarEvents}"
                )
            }
        }
    }

    fun exportContacts(context: Context) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, message = null) }

            val shared = withContext(Dispatchers.IO) {
                val dao = AppDatabase.get(context).crmDao()
                val contacts = dao.getAllContacts()
                VcfExporter.exportAndShare(context, contacts, "${context.packageName}.fileprovider")
            }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    message = if (shared) "Exported contacts.vcf for sharing." else "No contacts available to export."
                )
            }
        }
    }
}
