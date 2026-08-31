package com.huyang.luciddream.network

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentExecutionPreflightTest {
    private val preflight = AgentExecutionPreflight(
        agentHealthClient = AgentHealthClient(okhttp3.OkHttpClient()),
        mobilerunHealthClient = MobilerunHealthClient(okhttp3.OkHttpClient()),
    )

    @Test
    fun bothLocalServicesReadyPassesPreflight() = runTest {
        val result = preflight.checkWith(
            agentCheck = { AgentHealthResult.Success },
            mobilerunCheck = { MobilerunHealthResult.Success },
        )

        assertEquals(AgentExecutionPreflightResult.Ready, result)
    }

    @Test
    fun agentServerOfflineFailsBeforeMobilerunCheck() = runTest {
        var mobilerunCalled = false
        val result = preflight.checkWith(
            agentCheck = { AgentHealthResult.Failure("Connection refused") },
            mobilerunCheck = {
                mobilerunCalled = true
                MobilerunHealthResult.Success
            },
        )

        assertFalse(mobilerunCalled)
        assertTrue(result is AgentExecutionPreflightResult.Failure)
        assertEquals(
            "Android Agent 服务未运行：Connection refused",
            (result as AgentExecutionPreflightResult.Failure).message,
        )
    }

    @Test
    fun mobilerunOfflineReturnsSpecificDiagnostic() = runTest {
        val result = preflight.checkWith(
            agentCheck = { AgentHealthResult.Success },
            mobilerunCheck = { MobilerunHealthResult.Failure("Connection refused") },
        )

        assertTrue(result is AgentExecutionPreflightResult.Failure)
        assertEquals(
            "Mobilerun Portal REST Server 未运行：Connection refused",
            (result as AgentExecutionPreflightResult.Failure).message,
        )
    }
}
