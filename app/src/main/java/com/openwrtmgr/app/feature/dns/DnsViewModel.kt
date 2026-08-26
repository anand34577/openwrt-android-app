package com.openwrtmgr.app.feature.dns

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openwrtmgr.app.domain.model.DnsRecord
import com.openwrtmgr.app.domain.repository.RouterRepository
import com.openwrtmgr.app.ui.components.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DnsViewModel(
    private val repository: RouterRepository,
    private val profileId: Long,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<DnsRecord>>>(UiState.Loading)
    val state: StateFlow<UiState<List<DnsRecord>>> = _state.asStateFlow()

    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

    init {
        refresh()
    }

    fun consumeActionMessage() {
        _actionMessage.value = null
    }

    fun refresh() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            repository.clientFor(profileId)
                .mapCatching { it.getDnsRecords().getOrThrow() }
                .onSuccess { _state.value = UiState.Loaded(it) }
                .onFailure { _state.value = UiState.Error(it.message ?: "Something went wrong") }
        }
    }

    fun save(record: DnsRecord) = viewModelScope.launch {
        repository.clientFor(profileId)
            .mapCatching { it.saveDnsRecord(record).getOrThrow() }
            .onSuccess { refresh() }
            .onFailure { _actionMessage.value = it.message ?: "Couldn't save that record" }
    }

    fun delete(record: DnsRecord) {
        val sectionId = record.uciSectionId ?: return
        viewModelScope.launch {
            repository.clientFor(profileId)
                .mapCatching { it.deleteDnsRecord(sectionId).getOrThrow() }
                .onSuccess { refresh() }
                .onFailure { _actionMessage.value = it.message ?: "Couldn't delete that record" }
        }
    }
}
