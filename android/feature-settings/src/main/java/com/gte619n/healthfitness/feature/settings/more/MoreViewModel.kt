package com.gte619n.healthfitness.feature.settings.more

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.data.auth.GoogleAuthRepository
import com.gte619n.healthfitness.domain.profile.Profile
import com.gte619n.healthfitness.domain.profile.ProfileRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Backs the More overflow screen. Two responsibilities:
 *
 *  - Surface the signed-in user's name/email so the screen can render a
 *    small identity header at the top — matches the convention of
 *    native Android overflow menus and avoids forcing the user back to
 *    Settings → Profile just to confirm which account they're in.
 *  - Own the sign-out coroutine so the screen can keep its `onSignedOut`
 *    callback dumb (just `navController` pop-to-root via the lambda
 *    threaded through `AppRoot`).
 *
 * Identity fetch is a single-shot `GET /api/me` via [ProfileRepository];
 * failures collapse into [UiState.NoProfile] rather than [UiState.Error]
 * because the menu rows still need to render even without the header.
 *
 * Sign-out is injected as a [SignOutAction] rather than the raw
 * [GoogleAuthRepository] so the view-model is testable without
 * standing up a Credential Manager fake — the Hilt binding lives in
 * [MoreModule] below.
 */
@HiltViewModel
class MoreViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val signOutAction: SignOutAction,
) : ViewModel() {

    sealed interface UiState {
        data object Loading : UiState
        data class Loaded(val profile: Profile) : UiState

        /**
         * Render the menu without an identity header. Used both for
         * "request still in flight long enough that we'd rather show
         * the menu" and the failure path; the rows below don't depend
         * on profile data so this is the right degradation.
         */
        data object NoProfile : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = UiState.Loading
            profileRepository.get().fold(
                onSuccess = { _state.value = UiState.Loaded(it) },
                onFailure = { _state.value = UiState.NoProfile },
            )
        }
    }

    fun signOut(onDone: () -> Unit) {
        viewModelScope.launch {
            signOutAction.invoke()
            onDone()
        }
    }
}

/**
 * Thin sign-out indirection so [MoreViewModel] doesn't have to hold a
 * direct reference to the non-open [GoogleAuthRepository] (which would
 * make it impractical to instantiate in tests).
 */
fun interface SignOutAction {
    suspend operator fun invoke()
}

@Module
@InstallIn(SingletonComponent::class)
object MoreModule {
    /**
     * Bind [SignOutAction] to the real [GoogleAuthRepository.signOut].
     * Lives in the More feature module so the binding is co-located
     * with its only consumer; if/when a second screen needs the same
     * indirection it can move to a shared `core-data` module.
     */
    @Provides
    fun provideSignOutAction(authRepository: GoogleAuthRepository): SignOutAction =
        SignOutAction { authRepository.signOut() }
}
