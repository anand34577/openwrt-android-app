package com.openwrtmgr.app.feature.uci

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openwrtmgr.app.domain.model.UciSection
import com.openwrtmgr.app.domain.repository.RouterRepository
import com.openwrtmgr.app.ui.components.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class UciEditorViewModel(
    private val repository: RouterRepository,
    private val profileId: Long,
) : ViewModel() {

    private val _config = MutableStateFlow("network")
    val config: StateFlow<String> = _config.asStateFlow()

    private val _state = MutableStateFlow<UiState<List<UciSection>>>(UiState.Loading)
    val state: StateFlow<UiState<List<UciSection>>> = _state.asStateFlow()

    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

    init {
        load()
    }

    fun consumeActionMessage() {
        _actionMessage.value = null
    }

    fun selectConfig(config: String) {
        _config.value = config
        load()
    }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            repository.clientFor(profileId)
                .mapCatching { it.getUciConfig(_config.value).getOrThrow() }
                .onSuccess { _state.value = UiState.Loaded(it) }
                .onFailure { _state.value = UiState.Error(it.message ?: "Something went wrong") }
        }
    }

    fun setValues(section: String, values: Map<String, String>) = viewModelScope.launch {
        repository.clientFor(profileId)
            .mapCatching { it.setUciValues(_config.value, section, values).getOrThrow() }
            .onSuccess { load() }
            .onFailure { _actionMessage.value = it.message ?: "Couldn't save that section" }
    }

    fun deleteSection(section: String) = viewModelScope.launch {
        repository.clientFor(profileId)
            .mapCatching { it.deleteUciSection(_config.value, section).getOrThrow() }
            .onSuccess { load() }
            .onFailure { _actionMessage.value = it.message ?: "Couldn't delete that section" }
    }

    fun addSection(type: String) = viewModelScope.launch {
        repository.clientFor(profileId)
            .mapCatching { it.addUciSection(_config.value, type).getOrThrow() }
            .onSuccess { load() }
            .onFailure { _actionMessage.value = it.message ?: "Couldn't create that section" }
    }
}
