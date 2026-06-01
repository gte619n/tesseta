package com.gte619n.healthfitness.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.data.auth.GoogleAuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: GoogleAuthRepository,
    private val appVersionInfo: AppVersionInfo,
) : ViewModel() {

    val versionName: String = appVersionInfo.versionName
    val versionCode: Int = appVersionInfo.versionCode

    fun signOut(onDone: () -> Unit) {
        viewModelScope.launch {
            authRepository.signOut()
            onDone()
        }
    }
}
