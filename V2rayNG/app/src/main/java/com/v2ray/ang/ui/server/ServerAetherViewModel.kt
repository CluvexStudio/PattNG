package com.v2ray.ang.ui.server

import android.app.Application
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.R
import com.v2ray.ang.core.AetherCoreManager
import com.v2ray.ang.core.AetherIdentity
import com.v2ray.ang.core.AetherIdentityStatus
import com.v2ray.ang.core.AetherScanResult
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherProtocol
import com.v2ray.ang.ui.base.BaseViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

sealed interface AetherScanState {
    data object Idle : AetherScanState
    data object Scanning : AetherScanState
    data class Found(val result: AetherScanResult) : AetherScanState
    data object NotFound : AetherScanState
}

sealed interface AetherLogText {
    data class Raw(val value: String) : AetherLogText
    data class Resource(@StringRes val id: Int, val args: List<String> = emptyList()) : AetherLogText
}

data class AetherLogEntry(
    val id: Long,
    val priority: Int,
    val text: AetherLogText,
)

class ServerAetherViewModel(
    application: Application,
    private val source: AetherEditorSource,
) : BaseViewModel(application) {

    private val _isCoreAvailable = MutableStateFlow(true)
    val isCoreAvailable: StateFlow<Boolean> = _isCoreAvailable.asStateFlow()

    private val _scanState = MutableStateFlow<AetherScanState>(AetherScanState.Idle)
    val scanState: StateFlow<AetherScanState> = _scanState.asStateFlow()

    private val _isRenewingIdentity = MutableStateFlow(false)
    val isRenewingIdentity: StateFlow<Boolean> = _isRenewingIdentity.asStateFlow()

    /** The daemon's live Aether session, if any; the shared WARP key must not change under it. */
    private val _session = MutableStateFlow<AetherSession?>(null)
    val session: StateFlow<AetherSession?> = _session.asStateFlow()

    private val _log = MutableStateFlow<List<AetherLogEntry>>(emptyList())
    val log: StateFlow<List<AetherLogEntry>> = _log.asStateFlow()

    private val nextLogId = AtomicLong()
    private var scanJob: Job? = null
    private var reportedIdentity: AetherIdentityStatus? = null

    private val isBusy: Boolean
        get() = _scanState.value == AetherScanState.Scanning || _isRenewingIdentity.value

    init {
        viewModelScope.launch { _isCoreAvailable.value = source.isCoreAvailable() }
        refreshSession()
    }

    fun refreshSession() {
        viewModelScope.launch { _session.value = source.activeSession() }
    }

    fun scan(profile: ProfileItem) {
        if (isBusy) return
        _scanState.value = AetherScanState.Scanning
        scanJob = viewModelScope.launch {
            // Checked at the tap: a second tunnel on the key of a live session would disturb it.
            val session = source.activeSession()
            _session.value = session
            if (session?.disturbedByScanOf(AetherProtocol.fromString(profile.aetherProtocol)) == true) {
                _scanState.value = AetherScanState.Idle
                append(Log.WARN, AetherLogText.Resource(R.string.aether_scan_blocked))
                return@launch
            }
            append(Log.INFO, AetherLogText.Resource(R.string.aether_log_scan_started))
            val result = source.scan(profile, ::appendOutput)
            _scanState.value = result?.let(AetherScanState::Found) ?: AetherScanState.NotFound
            append(if (result == null) Log.WARN else Log.INFO, scanOutcome(result))
            reportIdentity(source.identityStatus(AetherProtocol.fromString(profile.aetherProtocol)), onlyChanges = true)
        }
    }

    fun cancelScan() {
        if (_scanState.value != AetherScanState.Scanning) return
        scanJob?.cancel()
        _scanState.value = AetherScanState.Idle
        append(Log.WARN, AetherLogText.Resource(R.string.aether_log_scan_cancelled))
    }

    fun onScanHandled() {
        if (_scanState.value != AetherScanState.Scanning) {
            _scanState.value = AetherScanState.Idle
        }
    }

    fun showIdentity(protocol: AetherProtocol) {
        viewModelScope.launch { reportIdentity(source.identityStatus(protocol), onlyChanges = true) }
    }

    fun renewIdentity(profile: ProfileItem) {
        if (isBusy) return
        _isRenewingIdentity.value = true
        viewModelScope.launch {
            try {
                // Checked again at the tap, the session may have come up after the screen opened.
                val session = source.activeSession()
                _session.value = session
                if (session != null) {
                    append(Log.WARN, AetherLogText.Resource(R.string.aether_renew_blocked))
                    return@launch
                }
                append(Log.INFO, AetherLogText.Resource(R.string.aether_log_key_renewing))
                val status = source.renewIdentity(profile, ::appendOutput)
                if (status == null) {
                    append(Log.ERROR, AetherLogText.Resource(R.string.aether_log_key_renew_failed))
                } else {
                    append(Log.INFO, AetherLogText.Resource(R.string.aether_log_key_renewed))
                    reportIdentity(status, onlyChanges = false)
                }
            } finally {
                _isRenewingIdentity.value = false
            }
        }
    }

    private fun reportIdentity(status: AetherIdentityStatus, onlyChanges: Boolean) {
        if (onlyChanges && status == reportedIdentity) return
        reportedIdentity = status
        identityLines(status).forEach { append(Log.INFO, it) }
    }

    private fun appendOutput(line: String) {
        val message = AetherCoreManager.outputMessage(line)
        if (message.isNotEmpty()) {
            append(AetherCoreManager.outputPriority(line.trim()), AetherLogText.Raw(message))
        }
    }

    private fun append(priority: Int, text: AetherLogText) {
        val entry = AetherLogEntry(nextLogId.incrementAndGet(), priority, text)
        _log.update { (it + entry).takeLast(LOG_CAPACITY) }
    }

    companion object {
        internal const val LOG_CAPACITY = 500
        private const val DEVICE_ID_LENGTH = 8

        internal fun scanOutcome(result: AetherScanResult?): AetherLogText.Resource {
            val innerHop = result?.innerHop
            return when {
                result == null -> AetherLogText.Resource(R.string.aether_scan_failed)
                innerHop != null -> AetherLogText.Resource(
                    R.string.aether_log_scan_found_hops,
                    listOf(result.endpoint.toString(), innerHop.toString())
                )

                else -> AetherLogText.Resource(R.string.aether_log_scan_found, listOf(result.endpoint.toString()))
            }
        }

        internal fun identityLines(status: AetherIdentityStatus): List<AetherLogText.Resource> = when (status.protocol) {
            AetherProtocol.MASQUE -> listOf(
                keyLine(status.primary, R.string.aether_log_masque_key_ready, R.string.aether_log_masque_key_missing)
            )

            AetherProtocol.WIREGUARD -> listOf(
                keyLine(status.primary, R.string.aether_log_wireguard_key_ready, R.string.aether_log_wireguard_key_missing)
            )

            AetherProtocol.GOOL -> listOf(
                keyLine(status.primary, R.string.aether_log_outer_key_ready, R.string.aether_log_outer_key_missing),
                keyLine(status.secondary, R.string.aether_log_inner_key_ready, R.string.aether_log_inner_key_missing),
            )
        }

        private fun keyLine(identity: AetherIdentity?, @StringRes ready: Int, @StringRes missing: Int): AetherLogText.Resource =
            if (identity == null) {
                AetherLogText.Resource(missing)
            } else {
                AetherLogText.Resource(ready, listOf(shortDeviceId(identity.deviceId), identity.ipv4, identity.ipv6))
            }

        private fun shortDeviceId(deviceId: String): String =
            if (deviceId.length > DEVICE_ID_LENGTH) deviceId.take(DEVICE_ID_LENGTH) + "…" else deviceId
    }
}
