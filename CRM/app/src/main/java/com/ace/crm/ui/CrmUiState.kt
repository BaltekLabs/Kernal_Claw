package com.ace.crm.ui

data class CrmUiState(
    val contactCount: Int = 0,
    val callLogCount: Int = 0,
    val smsCount: Int = 0,
    val isLoading: Boolean = false,
    val message: String? = null
)
