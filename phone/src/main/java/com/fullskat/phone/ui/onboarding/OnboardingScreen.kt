package com.fullskat.phone.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fullskat.core.ServerEndpoint
import com.fullskat.core.net.ApiException
import com.fullskat.core.ui.theme.Ember
import com.fullskat.phone.PhoneContainer
import com.fullskat.phone.R
import com.fullskat.phone.ui.durationText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Why sign-up failed. */
sealed interface SignUpError {
    data object WrongPasscode : SignUpError
    data class Locked(val retryAfterSec: Int) : SignUpError
    data object Offline : SignUpError
    data class Server(val message: String) : SignUpError
}

class OnboardingViewModel(private val app: PhoneContainer) : ViewModel() {
    var name by mutableStateOf("")
        private set
    var passcode by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<SignUpError?>(null)
        private set

    val canSubmit: Boolean get() = !busy && name.trim().length in 1..MAX_NAME && passcode.length in 4..8

    fun onName(value: String) {
        name = value.take(MAX_NAME)
        error = null
    }

    fun onPasscode(value: String) {
        passcode = value.filter { it.isDigit() }.take(8)
        error = null
    }

    /** Creates the account; [onDone] gets whether this was the first account on the server (the passcode was set). */
    fun register(onDone: (firstSetup: Boolean) -> Unit) {
        if (!canSubmit) return
        busy = true
        error = null
        viewModelScope.launch {
            try {
                val session = app.core.account.register(passcode, name.trim(), app.deviceName)
                app.onSignedIn()
                onDone(session.firstSetup)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException.WrongPasscode) {
                error = SignUpError.WrongPasscode
            } catch (e: ApiException.Locked) {
                error = SignUpError.Locked(e.retryAfterSec)
            } catch (e: ApiException.RateLimited) {
                error = SignUpError.Locked(e.retryAfterSec)
            } catch (e: ApiException.Network) {
                error = SignUpError.Offline
            } catch (e: ApiException) {
                error = SignUpError.Server(e.message.orEmpty())
            } finally {
                busy = false
            }
        }
    }

    internal fun showError(e: SignUpError?) {
        error = e
    }

    companion object {
        const val MAX_NAME = 40
    }
}

@Composable
fun OnboardingScreen(vm: OnboardingViewModel, onDone: (firstSetup: Boolean) -> Unit) {
    val focus = LocalFocusManager.current
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(88.dp).background(Ember.accent.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_mic), contentDescription = null, tint = Ember.accent, modifier = Modifier.size(44.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, color = Ember.accent, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.onboarding_intro),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            OutlinedTextField(
                value = vm.name,
                onValueChange = vm::onName,
                label = { Text(stringResource(R.string.onboarding_name)) },
                singleLine = true,
                enabled = !vm.busy,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = vm.passcode,
                onValueChange = vm::onPasscode,
                label = { Text(stringResource(R.string.onboarding_passcode)) },
                supportingText = { Text(stringResource(R.string.onboarding_passcode_hint)) },
                singleLine = true,
                enabled = !vm.busy,
                isError = vm.error is SignUpError.WrongPasscode,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    focus.clearFocus()
                    vm.register(onDone)
                }),
                modifier = Modifier.fillMaxWidth(),
            )
            vm.error?.let { e ->
                Spacer(Modifier.height(8.dp))
                Text(
                    signUpErrorText(e),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    focus.clearFocus()
                    vm.register(onDone)
                },
                enabled = vm.canSubmit,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (vm.busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.onPrimary)
                else Text(stringResource(R.string.onboarding_sign_up), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(28.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp))
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Default.Info, contentDescription = null, tint = Ember.label, modifier = Modifier.size(20.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.onboarding_first_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        stringResource(R.string.onboarding_server, ServerEndpoint.URL.removePrefix("https://")),
                        style = MaterialTheme.typography.bodySmall,
                        color = Ember.label,
                    )
                }
            }
        }
    }
}

@Composable
private fun signUpErrorText(e: SignUpError): String = when (e) {
    SignUpError.WrongPasscode -> stringResource(R.string.onboarding_wrong)
    is SignUpError.Locked -> stringResource(R.string.onboarding_locked, durationText(e.retryAfterSec))
    SignUpError.Offline -> stringResource(R.string.onboarding_offline)
    is SignUpError.Server -> stringResource(R.string.onboarding_server_error, e.message)
}
