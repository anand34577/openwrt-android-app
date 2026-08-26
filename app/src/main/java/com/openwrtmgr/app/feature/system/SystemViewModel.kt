package com.openwrtmgr.app.feature.system

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openwrtmgr.app.core.networking.OpenWrtClient
import com.openwrtmgr.app.domain.model.LogEntry
import com.openwrtmgr.app.domain.model.Package
import com.openwrtmgr.app.domain.model.PackageActionResult
import com.openwrtmgr.app.domain.model.ServiceStatus
import com.openwrtmgr.app.domain.repository.RouterRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ServicesUiState {
    data object Loading : ServicesUiState
    /** [busy] is the service name currently being started/stopped/restarted, if any. */
    data class Loaded(val services: List<ServiceStatus>, val busy: String? = null, val actionMessage: String? = null) : ServicesUiState
    data class Error(val message: String) : ServicesUiState
}

sealed interface LogsUiState {
    data object Loading : LogsUiState
    data class Loaded(val entries: List<LogEntry>) : LogsUiState
    data class Error(val message: String) : LogsUiState
}

sealed interface PackagesUiState {
    data object Loading : PackagesUiState
    /** [busy] is the package name currently being installed/removed/updated, if any — one at a time. */
    data class Loaded(val packages: List<Package>, val busy: String? = null, val lastActionOutput: String? = null) : PackagesUiState
    data class Error(val message: String) : PackagesUiState
}

/** Backs Services, Logs, and Packages — same router client, split screens (section 19/20/21). */
class SystemViewModel(
    private val repository: RouterRepository,
    private val profileId: Long,
) : ViewModel() {

    private val _services = MutableStateFlow<ServicesUiState>(ServicesUiState.Loading)
    val services: StateFlow<ServicesUiState> = _services.asStateFlow()

    private val _logs = MutableStateFlow<LogsUiState>(LogsUiState.Loading)
    val logs: StateFlow<LogsUiState> = _logs.asStateFlow()

    private val _packages = MutableStateFlow<PackagesUiState>(PackagesUiState.Loading)
    val packages: StateFlow<PackagesUiState> = _packages.asStateFlow()

    init {
        refreshServices()
    }

    fun refreshServices() {
        _services.value = ServicesUiState.Loading
        viewModelScope.launch {
            repository.clientFor(profileId)
                .mapCatching { it.getServices().getOrThrow() }
                .onSuccess { _services.value = ServicesUiState.Loaded(it) }
                .onFailure { _services.value = ServicesUiState.Error(it.message ?: "Something went wrong") }
        }
    }

    /** Lazy: logs are only fetched once the user actually opens the Logs view. */
    fun refreshLogs() {
        _logs.value = LogsUiState.Loading
        viewModelScope.launch {
            repository.clientFor(profileId)
                .mapCatching { it.getLogs(lines = 300).getOrThrow() }
                .onSuccess { entries -> _logs.value = LogsUiState.Loaded(entries.sortedByDescending { it.epochSeconds }) }
                .onFailure { _logs.value = LogsUiState.Error(it.message ?: "Something went wrong") }
        }
    }

    /** Lazy: packages need SSH, only fetched once the user opens the Packages view. */
    fun refreshPackages() {
        _packages.value = PackagesUiState.Loading
        viewModelScope.launch {
            repository.clientFor(profileId)
                .mapCatching { it.getInstalledPackages().getOrThrow() }
                .onSuccess { pkgs -> _packages.value = PackagesUiState.Loaded(pkgs.sortedBy { it.name }) }
                .onFailure { _packages.value = PackagesUiState.Error(it.message ?: "Something went wrong") }
        }
    }

    fun startService(name: String) = runServiceAction(name) { it.startService(name) }
    fun stopService(name: String) = runServiceAction(name) { it.stopService(name) }
    fun restartService(name: String) = runServiceAction(name) { it.restartService(name) }

    private fun runServiceAction(name: String, action: suspend (OpenWrtClient) -> Result<PackageActionResult>) {
        val current = _services.value as? ServicesUiState.Loaded ?: return
        _services.value = current.copy(busy = name)
        viewModelScope.launch {
            repository.clientFor(profileId)
                .mapCatching { action(it).getOrThrow() }
                .onSuccess { result ->
                    val services = repository.clientFor(profileId).mapCatching { it.getServices().getOrThrow() }.getOrDefault(current.services)
                    _services.value = ServicesUiState.Loaded(services, actionMessage = result.output.ifBlank { "Done." })
                }
                .onFailure { failure ->
                    _services.value = current.copy(busy = null, actionMessage = failure.message ?: "Action failed")
                }
        }
    }

    fun consumeServiceActionMessage() {
        val current = _services.value as? ServicesUiState.Loaded ?: return
        _services.value = current.copy(actionMessage = null)
    }

    fun refreshPackageLists() = runPackageAction(busyLabel = "apk update") { it.refreshPackageLists() }
    fun installPackage(name: String) = runPackageAction(busyLabel = name) { it.installPackage(name) }
    fun removePackage(name: String) = runPackageAction(busyLabel = name) { it.removePackage(name) }

    private fun runPackageAction(
        busyLabel: String,
        action: suspend (OpenWrtClient) -> Result<PackageActionResult>,
    ) {
        val current = _packages.value as? PackagesUiState.Loaded ?: return
        _packages.value = current.copy(busy = busyLabel)
        viewModelScope.launch {
            repository.clientFor(profileId)
                .mapCatching { action(it).getOrThrow() }
                .onSuccess { result ->
                    refreshPackages() // re-fetch the real list rather than guess what changed
                    val after = _packages.value as? PackagesUiState.Loaded ?: return@onSuccess
                    _packages.value = after.copy(lastActionOutput = result.output.ifBlank { "Done." })
                }
                .onFailure { failure ->
                    val onError = _packages.value as? PackagesUiState.Loaded ?: current
                    _packages.value = onError.copy(busy = null, lastActionOutput = failure.message)
                }
        }
    }
}
