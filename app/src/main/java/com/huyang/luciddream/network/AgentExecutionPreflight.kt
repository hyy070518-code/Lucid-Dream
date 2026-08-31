package com.huyang.luciddream.network

import javax.inject.Inject
import javax.inject.Singleton

sealed interface AgentExecutionPreflightResult {
    data object Ready : AgentExecutionPreflightResult
    data class Failure(val message: String) : AgentExecutionPreflightResult
}

@Singleton
class AgentExecutionPreflight @Inject constructor(
    private val agentHealthClient: AgentHealthClient,
    private val mobilerunHealthClient: MobilerunHealthClient,
) {
    suspend fun check(): AgentExecutionPreflightResult = checkWith(
        agentCheck = agentHealthClient::check,
        mobilerunCheck = mobilerunHealthClient::check,
    )

    internal suspend fun checkWith(
        agentCheck: suspend () -> AgentHealthResult,
        mobilerunCheck: suspend () -> MobilerunHealthResult,
    ): AgentExecutionPreflightResult {
        when (val result = agentCheck()) {
            is AgentHealthResult.Failure -> return AgentExecutionPreflightResult.Failure(
                "Android Agent 服务未运行：${result.detail}",
            )
            AgentHealthResult.Success -> Unit
        }
        return when (val result = mobilerunCheck()) {
            is MobilerunHealthResult.Failure -> AgentExecutionPreflightResult.Failure(
                "Mobilerun Portal REST Server 未运行：${result.detail}",
            )
            MobilerunHealthResult.Success -> AgentExecutionPreflightResult.Ready
        }
    }
}
