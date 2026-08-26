package com.openwrtmgr.app.feature.clients

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openwrtmgr.app.domain.model.Client
import com.openwrtmgr.app.domain.repository.RouterRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ClientsUiState {
    data object Loading : ClientsUiState
    data class Loaded(val clients: List<Client>) : ClientsUiState
    data class Error(val message: String) : ClientsUiState
}

class ClientsViewModel(
    private val repository: RouterRepository,
    private val profileId: Long,
) : ViewModel() {

    private val _state = MutableStateFlow<ClientsUiState>(ClientsUiState.Loading)
    val state: StateFlow<ClientsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.value = ClientsUiState.Loading
        viewModelScope.launch {
            repository.clientFor(profileId)
                .mapCatching { it.getClients().getOrThrow() }
                .onSuccess { clients ->
                    // Section 13: online first, then alphabetically by whatever name we have.
                    val sorted = clients.sortedWith(
                        compareByDescending<Client> { it.wifi != null }
                            .thenBy { it.hostname ?: it.ipAddress ?: it.macAddress },
                    )
                    _state.value = ClientsUiState.Loaded(sorted)
                }
                .onFailure { _state.value = ClientsUiState.Error(it.message ?: "Something went wrong") }
        }
    }
}
