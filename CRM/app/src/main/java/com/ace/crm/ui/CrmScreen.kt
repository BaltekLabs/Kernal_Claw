package com.ace.crm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun CrmScreen(
    state: CrmUiState,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onRefresh: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Top
    ) {
        Text("ACE CRM", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(12.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Contacts: ${state.contactCount}")
                Text("Call logs: ${state.callLogCount}")
                Text("SMS: ${state.smsCount}")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
            Text("Import Device Data")
        }
        Spacer(modifier = Modifier.height(8.dp))

        Button(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
            Text("Export Contacts (VCF)")
        }
        Spacer(modifier = Modifier.height(8.dp))

        Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
            Text("Refresh")
        }

        if (state.isLoading) {
            Spacer(modifier = Modifier.height(12.dp))
            CircularProgressIndicator()
        }

        state.message?.let {
            Spacer(modifier = Modifier.height(12.dp))
            Text(it)
        }
    }
}
