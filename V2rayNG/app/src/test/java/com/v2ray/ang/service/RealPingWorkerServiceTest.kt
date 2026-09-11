package com.v2ray.ang.service

import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealPingWorkerServiceTest {

    @Test
    fun aetherProfilesAreLeftOutOfBatchTests() {
        assertFalse(isBatchTestable(EConfigType.AETHER))
        EConfigType.entries
            .filter { it != EConfigType.AETHER }
            .forEach { assertTrue(it.name, isBatchTestable(it)) }
    }
}
