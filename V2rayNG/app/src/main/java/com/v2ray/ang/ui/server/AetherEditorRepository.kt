package com.v2ray.ang.ui.server

import android.content.Context
import com.v2ray.ang.core.AetherCoreManager
import com.v2ray.ang.core.AetherIdentityManager
import com.v2ray.ang.core.AetherIdentityStatus
import com.v2ray.ang.core.AetherScanResult
import com.v2ray.ang.core.AetherScanner
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherProtocol
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface AetherEditorSource {
    suspend fun isCoreAvailable(): Boolean

    /** True while the daemon runs an Aether profile whose tunnel is up; every Aether profile shares that key. */
    suspend fun isSessionActive(): Boolean
    suspend fun scan(profile: ProfileItem, onOutput: (String) -> Unit): AetherScanResult?
    suspend fun identityStatus(protocol: AetherProtocol): AetherIdentityStatus
    suspend fun renewIdentity(profile: ProfileItem, onOutput: (String) -> Unit): AetherIdentityStatus?
}

class AetherEditorRepository(private val context: Context) : AetherEditorSource {

    override suspend fun isCoreAvailable(): Boolean =
        withContext(Dispatchers.IO) { AetherCoreManager.isSupported(context) }

    // The daemon is the only authority on its state, so this asks its listener instead of a UI-side flag.
    override suspend fun isSessionActive(): Boolean = withContext(Dispatchers.IO) {
        val selected = MmkvManager.getSelectServer()?.let(MmkvManager::decodeServerConfig)
        selected?.configType == EConfigType.AETHER && AetherCoreManager.acceptsConnections(AetherCoreManager.socksPort)
    }

    override suspend fun scan(profile: ProfileItem, onOutput: (String) -> Unit): AetherScanResult? =
        AetherScanner.scan(context, profile, onOutput)

    override suspend fun identityStatus(protocol: AetherProtocol): AetherIdentityStatus =
        AetherIdentityManager.status(context, protocol)

    override suspend fun renewIdentity(profile: ProfileItem, onOutput: (String) -> Unit): AetherIdentityStatus? =
        AetherIdentityManager.renew(context, profile, onOutput)
}
