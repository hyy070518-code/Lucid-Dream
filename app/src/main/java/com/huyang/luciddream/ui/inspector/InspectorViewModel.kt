package com.huyang.luciddream.ui.inspector

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huyang.luciddream.data.entity.AppEventEntity
import com.huyang.luciddream.data.repository.AppEventRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class InspectorViewModel @Inject constructor(
    eventRepository: AppEventRepository,
) : ViewModel() {
    val events: StateFlow<List<AppEventEntity>> = eventRepository.observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
