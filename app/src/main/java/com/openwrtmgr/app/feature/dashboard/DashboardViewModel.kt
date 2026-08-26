package com.openwrtmgr.app.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openwrtmgr.app.core.networking.OpenWrtClient
import com.openwrtmgr.app.domain.model.NetworkInterfaceInfo
import com.openwrtmgr.app.domain.model.SystemInfo
import com.openwrtmgr.app.domain.model.WifiRadio
import com.openwrtmgr.app.domain.repository.RouterRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface DashboardUiState {
    data object Loading : DashboardUiState
    data class Loaded(
        val systemInfo: SystemInfo,
        val interfaces: List<NetworkInterfaceInfo>,
        // Empty when the router has no iwinfo/wireless hardware — card simply doesn't render (section 43).
        val wifiRadios: List<WifiRadio>,
        val actionError: String? = null,
    ) : DashboardUiState
    data class Error(val message: String) : DashboardUiState
}

class DashboardViewModel(
    private val repository: RouterRepository,
    private val profileId: Long,
) : ViewModel() {

    private val _state = MutableStateFlow<DashboardUiState>(DashboardUiState.Loading)
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.value = DashboardUiState.Loading
        viewModelScope.launch {
            val result = repository.clientFor(profileId).mapCatching { client ->
                val info = client.getSystemInfo().getOrThrow()
                val interfaces = client.getInterfaces().getOrThrow()
                // Capability-gated (section 43): only ask for Wi-Fi data when the router actually
                // exposes netifd's wireless status — skips doomed round-trips on wired-only routers.
                val capabilities = client.getCapabilities().getOrNull()
                val wifiRadios = if (capabilities?.supports("network.wireless") == true) {
                    client.getWifiRadios().getOrDefault(emptyList())
                } else {
                    emptyList()
                }
                Triple(info, interfaces, wifiRadios)
            }
            result.onSuccess { (info, interfaces, wifiRadios) ->
                repository.markConnected(profileId)
                _state.value = DashboardUiState.Loaded(info, interfaces, wifiRadios)
            }.onFailure {
                _state.value = DashboardUiState.Error(it.message ?: "Something went wrong")
            }
        }
    }

    /** Section 11 — caller is responsible for confirming first; this just executes. */
    fun setInterfaceUp(name: String) = runAction { it.setInterfaceUp(name) }
    fun setInterfaceDown(name: String) = runAction { it.setInterfaceDown(name) }

    /** Section 12 — same: confirmation happens in the UI before this is called. */
    fun setRadioEnabled(device: String, enabled: Boolean) = runAction { it.setRadioEnabled(device, enabled) }

    /** Section 26 — reboot; UI must confirm before calling. Success just means the request was accepted. */
    fun reboot(onAccepted: () -> Unit) {
        viewModelScope.launch {
            repository.clientFor(profileId)
                .mapCatching { it.reboot().getOrThrow() }
                .onSuccess { onAccepted() }
                .onFailure { setActionError(it.message ?: "Couldn't reboot the router") }
        }
    }

    private fun runAction(action: suspend (OpenWrtClient) -> Result<Unit>) {
        viewModelScope.launch {
            repository.clientFor(profileId)
                .mapCatching { action(it).getOrThrow() }
                .onSuccess { refresh() }
                .onFailure { setActionError(it.message ?: "That action failed") }
        }
    }

    private fun setActionError(message: String) {
        val current = _state.value as? DashboardUiState.Loaded ?: return
        _state.value = current.copy(actionError = message)
    }
}
