package com.huyang.luciddream

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.huyang.luciddream.data.database.LucidDreamDatabase
import com.huyang.luciddream.data.entity.AppEventEntity
import com.huyang.luciddream.security.SecureApiKeyStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Phase1DeviceTest {
    private val testContext = InstrumentationRegistry.getInstrumentation().context

    @Test
    fun keystoreEncryptedCredentialRoundTripsInTestPackage() {
        val store = SecureApiKeyStore(testContext)
        store.clear()
        store.save("DEVICE_TEST_CREDENTIAL_PLACEHOLDER")

        assertEquals("DEVICE_TEST_CREDENTIAL_PLACEHOLDER", store.read())
        store.clear()
    }

    @Test
    fun roomPersistsInspectorEvent() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            testContext,
            LucidDreamDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            database.appEventDao().insert(
                AppEventEntity(
                    timestamp = 123L,
                    category = "TEST",
                    message = "Phase 1",
                ),
            )

            val events = database.appEventDao().observeRecent().first()
            assertTrue(events.isNotEmpty())
            assertEquals("Phase 1", events.first().message)
        } finally {
            database.close()
        }
    }
}
