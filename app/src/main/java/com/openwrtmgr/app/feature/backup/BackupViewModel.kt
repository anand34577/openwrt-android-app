package com.openwrtmgr.app.feature.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openwrtmgr.app.domain.repository.RouterRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface BackupStatus {
    data object Idle : BackupStatus
    data object Working : BackupStatus
    data class Message(val text: String) : BackupStatus
}

class BackupViewModel(
    private val repository: RouterRepository,
    private val profileId: Long,
) : ViewModel() {

    private val _status = MutableStateFlow<BackupStatus>(BackupStatus.Idle)
    val status: StateFlow<BackupStatus> = _status.asStateFlow()

    fun backup(onArchiveReady: suspend (ByteArray) -> Unit) {
        _status.value = BackupStatus.Working
        viewModelScope.launch {
            repository.clientFor(profileId)
                .mapCatching { it.backupConfig().getOrThrow() }
                .onSuccess {
                    onArchiveReady(it)
                    _status.value = BackupStatus.Message("Backup saved.")
                }
                .onFailure { _status.value = BackupStatus.Message(it.message ?: "Backup failed") }
        }
    }

    fun restore(archive: ByteArray) {
        _status.value = BackupStatus.Working
        viewModelScope.launch {
            repository.clientFor(profileId)
                .mapCatching { it.restoreConfig(archive).getOrThrow() }
                .onSuccess { _status.value = BackupStatus.Message("Restore applied. The router is rebooting.") }
                .onFailure { _status.value = BackupStatus.Message(it.message ?: "Restore failed") }
        }
    }

    fun consumeMessage() {
        if (_status.value is BackupStatus.Message) _status.value = BackupStatus.Idle
    }
}
