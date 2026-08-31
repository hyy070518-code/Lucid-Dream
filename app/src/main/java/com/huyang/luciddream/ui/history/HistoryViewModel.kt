package com.huyang.luciddream.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huyang.luciddream.data.entity.DelegationSessionEntity
import com.huyang.luciddream.session.DelegationManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class HistoryViewModel @Inject constructor(
    delegationManager: DelegationManager,
) : ViewModel() {
    val sessions: StateFlow<List<DelegationSessionEntity>> = delegationManager.recentSessions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
