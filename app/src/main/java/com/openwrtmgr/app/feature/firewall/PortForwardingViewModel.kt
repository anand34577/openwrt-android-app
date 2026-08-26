package com.openwrtmgr.app.feature.firewall

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openwrtmgr.app.domain.model.PortForward
import com.openwrtmgr.app.domain.repository.RouterRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PortForwardingUiState {
    data object Loading : PortForwardingUiState
    data class Loaded(val rules: List<PortForward>, val savingError: String? = null) : PortForwardingUiState
    data class Error(val message: String) : PortForwardingUiState
}

class PortForwardingViewModel(
    private val repository: RouterRepository,
    private val profileId: Long,
) : ViewModel() {

    private val _state = MutableStateFlow<PortForwardingUiState>(PortForwardingUiState.Loading)
    val state: StateFlow<PortForwardingUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.value = PortForwardingUiState.Loading
        viewModelScope.launch {
            repository.clientFor(profileId)
                .mapCatching { it.getPortForwards().getOrThrow() }
                .onSuccess { _state.value = PortForwardingUiState.Loaded(it) }
                .onFailure { _state.value = PortForwardingUiState.Error(it.message ?: "Something went wrong") }
        }
    }

    fun save(rule: PortForward) {
        viewModelScope.launch {
            repository.clientFor(profileId)
                .mapCatching { it.savePortForward(rule).getOrThrow() }
                .onSuccess { refresh() }
                .onFailure { failure ->
                    val current = _state.value as? PortForwardingUiState.Loaded ?: return@onFailure
                    _state.value = current.copy(savingError = failure.message ?: "Couldn't save that rule")
                }
        }
    }

    fun delete(rule: PortForward) {
        val sectionId = rule.uciSectionId ?: return
        viewModelScope.launch {
            repository.clientFor(profileId)
                .mapCatching { it.deletePortForward(sectionId).getOrThrow() }
                .onSuccess { refresh() }
                .onFailure { failure ->
                    val current = _state.value as? PortForwardingUiState.Loaded ?: return@onFailure
                    _state.value = current.copy(savingError = failure.message ?: "Couldn't delete that rule")
                }
        }
    }
}
