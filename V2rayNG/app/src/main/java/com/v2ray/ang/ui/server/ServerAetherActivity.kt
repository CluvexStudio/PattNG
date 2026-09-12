package com.v2ray.ang.ui.server

import android.content.Context
import android.util.Log
import androidx.activity.viewModels
import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.v2ray.ang.R
import com.v2ray.ang.core.AetherScanResult
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherProtocol
import com.v2ray.ang.enums.AetherTransport
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.fmt.AetherFmt
import com.v2ray.ang.ui.compose.ConfirmDialog
import com.v2ray.ang.ui.compose.FormDropdownField
import com.v2ray.ang.ui.compose.FormTextField
import com.v2ray.ang.ui.compose.SettingsSwitchItem
import com.v2ray.ang.ui.compose.verticalScrollbar
import com.v2ray.ang.util.Utils

class ServerAetherActivity : BaseServerActivity() {

    override val serverConfigType: EConfigType = EConfigType.AETHER

    private val viewModel: ServerAetherViewModel by viewModels {
        viewModelFactory {
            initializer { ServerAetherViewModel(application, AetherEditorRepository(application)) }
        }
    }

    @Composable
    override fun ScreenContent() {
        val uiState = rememberSaveable(saver = ServerUiState.Saver) {
            ServerUiState.from(initialConfig = initialConfig)
        }.apply {
            configType = serverConfigType
        }
        val isCoreAvailable by viewModel.isCoreAvailable.collectAsStateWithLifecycle()
        val scanState by viewModel.scanState.collectAsStateWithLifecycle()
        val isRenewingIdentity by viewModel.isRenewingIdentity.collectAsStateWithLifecycle()
        val isSessionActive by viewModel.isSessionActive.collectAsStateWithLifecycle()
        val log by viewModel.log.collectAsStateWithLifecycle()
        var showRenewConfirm by rememberSaveable { mutableStateOf(false) }
        val isScanning = scanState == AetherScanState.Scanning
        val isBusy = isScanning || isRenewingIdentity
        // The key is shared by every Aether profile, so a live session on any of them blocks renewal.
        val renewBlocked = isSessionActive || isRunning

        LaunchedEffect(Unit) {
            viewModel.refreshSession()
        }

        val protocol = AetherProtocol.fromString(uiState.aetherProtocol)
        val usesHttp2 = protocol == AetherProtocol.MASQUE &&
            AetherTransport.fromString(uiState.aetherTransport) == AetherTransport.HTTP2

        LaunchedEffect(protocol) {
            viewModel.showIdentity(protocol)
        }

        LaunchedEffect(scanState) {
            when (val state = scanState) {
                is AetherScanState.Found -> {
                    applyScanResult(uiState, state.result)
                    toastSuccess(R.string.aether_scan_success)
                    viewModel.onScanHandled()
                }

                AetherScanState.NotFound -> {
                    toastError(R.string.aether_scan_failed)
                    viewModel.onScanHandled()
                }

                AetherScanState.Idle, AetherScanState.Scanning -> Unit
            }
        }

        ServerEditorScaffold(
            title = serverConfigType.toString(),
            onSaveClick = { saveServer(uiState) }
        ) {
            FormTextField(
                stringResource(R.string.server_lab_remarks),
                uiState.remarks,
                { uiState.remarks = it }
            )
            AetherDropdownField(
                label = R.string.aether_lab_protocol,
                value = uiState.aetherProtocol,
                entries = R.array.aether_protocol_entries,
                values = R.array.aether_protocol_values,
                enabled = !isBusy,
                onValueChange = { uiState.aetherProtocol = it }
            )
            if (protocol == AetherProtocol.MASQUE) {
                AetherDropdownField(
                    label = R.string.aether_lab_transport,
                    value = uiState.aetherTransport,
                    entries = R.array.aether_transport_entries,
                    values = R.array.aether_transport_values,
                    onValueChange = { uiState.aetherTransport = it }
                )
            }
            if (usesHttp2) {
                SettingsSwitchItem(
                    title = stringResource(R.string.aether_lab_fragment),
                    checked = uiState.aetherFragment,
                    onCheckedChange = { uiState.aetherFragment = it }
                )
                if (uiState.aetherFragment) {
                    FormTextField(
                        stringResource(R.string.aether_lab_fragment_size),
                        uiState.aetherFragmentSize,
                        { uiState.aetherFragmentSize = it },
                        placeholder = stringResource(R.string.aether_hint_fragment_size)
                    )
                    FormTextField(
                        stringResource(R.string.aether_lab_fragment_delay),
                        uiState.aetherFragmentDelay,
                        { uiState.aetherFragmentDelay = it },
                        placeholder = stringResource(R.string.aether_hint_fragment_delay)
                    )
                }
            }
            AetherDropdownField(
                label = R.string.aether_lab_scan_mode,
                value = uiState.aetherScanMode,
                entries = R.array.aether_scan_entries,
                values = R.array.aether_scan_values,
                onValueChange = { uiState.aetherScanMode = it }
            )
            AetherDropdownField(
                label = R.string.aether_lab_obfuscation,
                value = uiState.aetherObfuscation,
                entries = R.array.aether_obfuscation_entries,
                values = R.array.aether_obfuscation_values,
                onValueChange = { uiState.aetherObfuscation = it }
            )
            AetherDropdownField(
                label = R.string.aether_lab_ip_version,
                value = uiState.aetherIpVersion,
                entries = R.array.aether_ip_entries,
                values = R.array.aether_ip_values,
                onValueChange = { uiState.aetherIpVersion = it }
            )
            if (protocol == AetherProtocol.GOOL) {
                FormTextField(
                    stringResource(R.string.aether_lab_wiw_outer),
                    uiState.aetherWiwOuter,
                    { uiState.aetherWiwOuter = it },
                    placeholder = stringResource(R.string.aether_hint_endpoint)
                )
                FormTextField(
                    stringResource(R.string.aether_lab_wiw_inner),
                    uiState.aetherWiwInner,
                    { uiState.aetherWiwInner = it },
                    placeholder = stringResource(R.string.aether_hint_endpoint)
                )
            } else {
                FormTextField(
                    stringResource(R.string.server_lab_address),
                    uiState.address,
                    { uiState.address = it },
                    placeholder = stringResource(R.string.aether_hint_endpoint)
                )
                FormTextField(
                    stringResource(R.string.server_lab_port),
                    uiState.port,
                    { uiState.port = it },
                    keyboardType = KeyboardType.Number
                )
            }
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { viewModel.scan(uiState.toProfileItem(initialConfig)) },
                    enabled = isCoreAvailable && !isBusy
                ) {
                    if (isScanning) {
                        ProgressMark()
                    }
                    Text(stringResource(if (isScanning) R.string.aether_action_scanning else R.string.aether_action_scan))
                }
                if (isScanning) {
                    TextButton(onClick = viewModel::cancelScan) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }
            OutlinedButton(
                onClick = { showRenewConfirm = true },
                enabled = isCoreAvailable && !isBusy && !renewBlocked,
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                if (isRenewingIdentity) {
                    ProgressMark()
                }
                Text(
                    stringResource(
                        if (isRenewingIdentity) R.string.aether_action_renewing_key else R.string.aether_action_renew_key
                    )
                )
            }
            if (renewBlocked) {
                Text(
                    text = stringResource(R.string.aether_renew_blocked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
            if (!isCoreAvailable) {
                Text(
                    text = stringResource(R.string.aether_unsupported_abi),
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
            AetherLogPanel(entries = log)
        }

        if (showRenewConfirm) {
            ConfirmDialog(
                message = stringResource(R.string.aether_confirm_renew_key),
                confirmText = stringResource(R.string.aether_action_renew_key),
                onConfirm = {
                    showRenewConfirm = false
                    viewModel.renewIdentity(uiState.toProfileItem(initialConfig))
                },
                onDismiss = { showRenewConfirm = false }
            )
        }
    }

    override fun validateBasicConfig(state: ServerUiState): Boolean {
        if (state.remarks.isBlank()) {
            toast(R.string.server_lab_remarks)
            return false
        }
        return true
    }

    override fun validateProtocolConfig(config: ProfileItem): Boolean {
        val problem = AetherFmt.normalize(config) ?: return true
        toast(
            when (problem) {
                AetherFmt.Problem.INVALID_PEER -> R.string.aether_invalid_endpoint
                AetherFmt.Problem.INVALID_HOP -> R.string.aether_invalid_hop
                AetherFmt.Problem.SHARED_HOP -> R.string.aether_same_hop
                AetherFmt.Problem.INVALID_FRAGMENT -> R.string.aether_invalid_fragment
            }
        )
        return false
    }

    private fun applyScanResult(state: ServerUiState, result: AetherScanResult) {
        if (AetherProtocol.fromString(state.aetherProtocol) == AetherProtocol.GOOL) {
            state.aetherWiwOuter = result.endpoint.toString()
            state.aetherWiwInner = result.innerHop?.toString().orEmpty()
        } else {
            state.address = result.endpoint.host
            state.port = result.endpoint.port.toString()
        }
    }
}

@Composable
private fun AetherDropdownField(
    @StringRes label: Int,
    value: String,
    @ArrayRes entries: Int,
    @ArrayRes values: Int,
    enabled: Boolean = true,
    onValueChange: (String) -> Unit,
) {
    val labels = stringArrayResource(entries)
    val options = stringArrayResource(values)
    FormDropdownField(
        label = stringResource(label),
        value = labels.getOrElse(options.indexOf(value).coerceAtLeast(0)) { "" },
        options = labels.toList(),
        onValueChange = { picked ->
            val index = labels.indexOf(picked)
            if (index >= 0) onValueChange(options[index])
        },
        enabled = enabled
    )
}

@Composable
private fun ProgressMark() {
    CircularProgressIndicator(
        modifier = Modifier
            .padding(end = 8.dp)
            .size(18.dp),
        strokeWidth = 2.dp
    )
}

@Composable
private fun AetherLogPanel(entries: List<AetherLogEntry>) {
    val context = LocalContext.current
    val listState = rememberLazyListState()

    LaunchedEffect(entries.lastOrNull()?.id) {
        if (entries.isNotEmpty()) listState.scrollToItem(entries.lastIndex)
    }

    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.aether_log_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = {
                    Utils.setClipboard(context, entries.joinToString("\n") { it.text.resolve(context) })
                    context.toastSuccess(R.string.toast_success)
                },
                enabled = entries.isNotEmpty()
            ) {
                Text(stringResource(R.string.logcat_copy))
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
        ) {
            if (entries.isEmpty()) {
                Text(
                    text = stringResource(R.string.aether_log_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScrollbar(listState),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(items = entries, key = { it.id }) { entry ->
                        Text(
                            text = entry.text.asString(),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = logColor(entry)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AetherLogText.asString(): String = when (this) {
    is AetherLogText.Raw -> value
    is AetherLogText.Resource -> stringResource(id, *args.toTypedArray())
}

private fun AetherLogText.resolve(context: Context): String = when (this) {
    is AetherLogText.Raw -> value
    is AetherLogText.Resource -> context.getString(id, *args.toTypedArray())
}

@Composable
private fun logColor(entry: AetherLogEntry): Color = when {
    entry.priority >= Log.ERROR -> MaterialTheme.colorScheme.error
    entry.priority == Log.WARN -> MaterialTheme.colorScheme.tertiary
    entry.text is AetherLogText.Resource -> MaterialTheme.colorScheme.onSurface
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
