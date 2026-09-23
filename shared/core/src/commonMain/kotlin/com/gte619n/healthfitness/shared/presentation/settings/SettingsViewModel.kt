package com.gte619n.healthfitness.shared.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.shared.data.AppInfo
import com.gte619n.healthfitness.shared.data.AuthRepository
import kotlinx.coroutines.launch

/**
 * IMPL-IOS-01 Phase 3 Wave A2 — shared port of the Android
 * `feature-settings/.../SettingsViewModel.kt`. The settings HUB: it surfaces the
 * build version (Settings › About) and drives sign-out. The units / coach-audio /
 * workout-prefs / device-connection surfaces each have their own ViewModel, same
 * as Android — this VM stays thin.
 */
class SettingsViewModel(
    private val authRepository: AuthRepository,
    appInfo: AppInfo,
) : ViewModel() {

    val versionName: String = appInfo.versionName
    val versionCode: Int = appInfo.versionCode

    fun signOut(onDone: () -> Unit) {
        viewModelScope.launch {
            authRepository.signOut()
            onDone()
        }
    }
}
