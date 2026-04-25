package com.rahga.x2rock.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rahga.x2rock.repository.SonosAuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class LoginUiState {
    data object Idle : LoginUiState()
    data object Loading : LoginUiState()
    data object Authenticated : LoginUiState()
    data class Error(val message: String) : LoginUiState()
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: SonosAuthRepository
) : ViewModel() {

    private val _state = MutableStateFlow<LoginUiState>(
        if (authRepository.isAuthenticated) LoginUiState.Authenticated else LoginUiState.Idle
    )
    val state: StateFlow<LoginUiState> = _state

    fun buildAuthUrl(): String = authRepository.buildAuthUrl()

    fun handleCallback(code: String, state: String) {
        viewModelScope.launch {
            _state.value = LoginUiState.Loading
            authRepository.exchangeCodeForTokens(code, state)
                .onSuccess { _state.value = LoginUiState.Authenticated }
                .onFailure { _state.value = LoginUiState.Error(it.message ?: "Authentication failed") }
        }
    }

    fun resetError() {
        if (_state.value is LoginUiState.Error) {
            _state.value = LoginUiState.Idle
        }
    }
}
