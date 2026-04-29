package com.ace.crm

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ace.crm.ui.CrmScreen
import com.ace.crm.ui.CrmViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: CrmViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            var hasRequestedPermissions by remember { mutableStateOf(false) }

            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) {
                viewModel.refreshSummary(this)
            }

            LaunchedEffect(Unit) {
                if (!hasRequestedPermissions) {
                    hasRequestedPermissions = true
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.READ_CONTACTS,
                            Manifest.permission.READ_CALL_LOG,
                            Manifest.permission.READ_SMS
                        )
                    )
                }
            }

            CrmScreen(
                state = uiState,
                onImport = { viewModel.importDeviceData(this) },
                onExport = { viewModel.exportContacts(this) },
                onRefresh = { viewModel.refreshSummary(this) }
            )
        }
    }
}
