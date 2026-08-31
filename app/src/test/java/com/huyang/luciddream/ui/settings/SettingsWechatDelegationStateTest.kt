package com.huyang.luciddream.ui.settings

import com.huyang.luciddream.data.entity.DelegationSessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsWechatDelegationStateTest {
    private val base = SettingsUiState(
        baseUrl = "https://api.deepseek.com/v1",
        model = "deepseek-v4-flash",
        hasApiKey = true,
    )

    @Test
    fun settingsRendersTheSameActiveSessionObjectFromDelegationManagerFlow() {
        val session = DelegationSessionEntity(
            id = 7,
            mode = DelegationSessionEntity.MODE_SLEEP,
            status = DelegationSessionEntity.STATUS_ACTIVE,
            startedAt = 123L,
            endedAt = null,
            defaultReplyLimit = 3,
        )

        assertEquals(session, base.withActiveDelegationSession(session).activeSession)
        assertEquals(null, base.withActiveDelegationSession(null).activeSession)
    }

    @Test
    fun settingsUsesSharedNotificationAccessAndListenerStateWithoutASecondSource() {
        val connected = base
            .withNotificationPermissions(accessGranted = true, summaryGranted = true)
            .withNotificationListenerConnection(connected = true)

        assertTrue(connected.notificationAccessGranted)
        assertTrue(connected.notificationListenerConnected)
        assertTrue(connected.summaryNotificationPermissionGranted)
        assertFalse(base.notificationListenerConnected)
    }
}
