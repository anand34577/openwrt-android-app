package com.openwrtmgr.app.feature.firewall

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openwrtmgr.app.domain.model.FirewallZone
import com.openwrtmgr.app.domain.model.PortForward
import com.openwrtmgr.app.domain.model.TrafficRule
import com.openwrtmgr.app.domain.repository.RouterRepository
import com.openwrtmgr.app.ui.components.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Backs all three Firewall tabs (Port Forwards / Traffic Rules / Zones) in one place since they
 * share a repository/profile and the same "reload firewall to apply" limitation.
 */
class FirewallViewModel(
    private val repository: RouterRepository,
    private val profileId: Long,
) : ViewModel() {

    private val _portForwards = MutableStateFlow<UiState<List<PortForward>>>(UiState.Loading)
    val portForwards: StateFlow<UiState<List<PortForward>>> = _portForwards.asStateFlow()

    private val _trafficRules = MutableStateFlow<UiState<List<TrafficRule>>>(UiState.Loading)
    val trafficRules: StateFlow<UiState<List<TrafficRule>>> = _trafficRules.asStateFlow()

    private val _zones = MutableStateFlow<UiState<List<FirewallZone>>>(UiState.Loading)
    val zones: StateFlow<UiState<List<FirewallZone>>> = _zones.asStateFlow()

    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

    private val _reloading = MutableStateFlow(false)
    val reloading: StateFlow<Boolean> = _reloading.asStateFlow()

    init {
        refreshPortForwards()
        refreshTrafficRules()
        refreshZones()
    }

    fun consumeActionMessage() {
        _actionMessage.value = null
    }

    fun refreshPortForwards() {
        _portForwards.value = UiState.Loading
        viewModelScope.launch {
            repository.clientFor(profileId)
                .mapCatching { it.getPortForwards().getOrThrow() }
                .onSuccess { _portForwards.value = UiState.Loaded(it) }
                .onFailure { _portForwards.value = UiState.Error(it.message ?: "Something went wrong") }
        }
    }

    fun savePortForward(rule: PortForward) = viewModelScope.launch {
        repository.clientFor(profileId)
            .mapCatching { it.savePortForward(rule).getOrThrow() }
            .onSuccess { refreshPortForwards() }
            .onFailure { _actionMessage.value = it.message ?: "Couldn't save that rule" }
    }

    fun deletePortForward(rule: PortForward) {
        val sectionId = rule.uciSectionId ?: return
        viewModelScope.launch {
            repository.clientFor(profileId)
                .mapCatching { it.deletePortForward(sectionId).getOrThrow() }
                .onSuccess { refreshPortForwards() }
                .onFailure { _actionMessage.value = it.message ?: "Couldn't delete that rule" }
        }
    }

    fun refreshTrafficRules() {
        _trafficRules.value = UiState.Loading
        viewModelScope.launch {
            repository.clientFor(profileId)
                .mapCatching { it.getTrafficRules().getOrThrow() }
                .onSuccess { _trafficRules.value = UiState.Loaded(it) }
                .onFailure { _trafficRules.value = UiState.Error(it.message ?: "Something went wrong") }
        }
    }

    fun saveTrafficRule(rule: TrafficRule) = viewModelScope.launch {
        repository.clientFor(profileId)
            .mapCatching { it.saveTrafficRule(rule).getOrThrow() }
            .onSuccess { refreshTrafficRules() }
            .onFailure { _actionMessage.value = it.message ?: "Couldn't save that rule" }
    }

    fun deleteTrafficRule(rule: TrafficRule) {
        val sectionId = rule.uciSectionId ?: return
        viewModelScope.launch {
            repository.clientFor(profileId)
                .mapCatching { it.deleteTrafficRule(sectionId).getOrThrow() }
                .onSuccess { refreshTrafficRules() }
                .onFailure { _actionMessage.value = it.message ?: "Couldn't delete that rule" }
        }
    }

    fun refreshZones() {
        _zones.value = UiState.Loading
        viewModelScope.launch {
            repository.clientFor(profileId)
                .mapCatching { it.getFirewallZones().getOrThrow() }
                .onSuccess { _zones.value = UiState.Loaded(it) }
                .onFailure { _zones.value = UiState.Error(it.message ?: "Something went wrong") }
        }
    }

    fun saveZone(zone: FirewallZone) = viewModelScope.launch {
        repository.clientFor(profileId)
            .mapCatching { it.saveFirewallZone(zone).getOrThrow() }
            .onSuccess { refreshZones() }
            .onFailure { _actionMessage.value = it.message ?: "Couldn't save that zone" }
    }

    fun deleteZone(zone: FirewallZone) {
        val sectionId = zone.uciSectionId ?: return
        viewModelScope.launch {
            repository.clientFor(profileId)
                .mapCatching { it.deleteFirewallZone(sectionId).getOrThrow() }
                .onSuccess { refreshZones() }
                .onFailure { _actionMessage.value = it.message ?: "Couldn't delete that zone" }
        }
    }

    fun reloadFirewall() {
        _reloading.value = true
        viewModelScope.launch {
            repository.clientFor(profileId)
                .mapCatching { it.reloadFirewall().getOrThrow() }
                .onSuccess { _actionMessage.value = "Firewall reloaded — changes are now live." }
                .onFailure { _actionMessage.value = it.message ?: "Couldn't reload the firewall" }
            _reloading.value = false
        }
    }
}
