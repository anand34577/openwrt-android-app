package com.openwrtmgr.app.ui.components

/** Generic Loading/Loaded/Error state for the newer, simpler screens (existing screens keep their own richer sealed types). */
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Loaded<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}
