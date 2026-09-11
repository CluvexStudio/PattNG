package com.v2ray.ang.ui.server

import android.content.Context
import com.v2ray.ang.core.AetherCoreManager
import com.v2ray.ang.core.AetherIdentityManager
import com.v2ray.ang.core.AetherIdentityStatus
import com.v2ray.ang.core.AetherScanResult
import com.v2ray.ang.core.AetherScanner
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface AetherEditorSource {
    suspend fun isCoreAvailable(): Boolean
    suspend fun scan(profile: ProfileItem, onOutput: (String) -> Unit): AetherScanResult?
    suspend fun identityStatus(protocol: AetherProtocol): AetherIdentityStatus
    suspend fun renewIdentity(profile: ProfileItem, onOutput: (String) -> Unit): AetherIdentityStatus?
}

class AetherEditorRepository(private val context: Context) : AetherEditorSource {

    override suspend fun isCoreAvailable(): Boolean =
        withContext(Dispatchers.IO) { AetherCoreManager.isSupported(context) }

    override suspend fun scan(profile: ProfileItem, onOutput: (String) -> Unit): AetherScanResult? =
        AetherScanner.scan(context, profile, onOutput)

    override suspend fun identityStatus(protocol: AetherProtocol): AetherIdentityStatus =
        AetherIdentityManager.status(context, protocol)

    override suspend fun renewIdentity(profile: ProfileItem, onOutput: (String) -> Unit): AetherIdentityStatus? =
        AetherIdentityManager.renew(context, profile, onOutput)
}
