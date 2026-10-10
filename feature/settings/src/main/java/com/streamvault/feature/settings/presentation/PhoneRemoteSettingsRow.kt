package com.streamvault.feature.settings.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streamvault.domain.remote.PhoneRemote
import com.streamvault.feature.settings.R
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class PhoneRemoteViewModel @Inject constructor(
    private val phoneRemote: PhoneRemote
) : ViewModel() {
    val enabled = phoneRemote.enabled
    val url = phoneRemote.url

    fun setEnabled(enabled: Boolean) = phoneRemote.setEnabled(enabled)
}

/** On/off for the phone remote; while on, the row shows the address to open on the phone. */
@Composable
fun PhoneRemoteSettingsRow(
    modifier: Modifier = Modifier,
    viewModel: PhoneRemoteViewModel = hiltViewModel()
) {
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val url by viewModel.url.collectAsStateWithLifecycle()
    SwitchSettingsRow(
        label = stringResource(R.string.settings_phone_remote),
        value = when {
            !enabled -> stringResource(R.string.settings_phone_remote_subtitle)
            url == null -> stringResource(R.string.settings_phone_remote_no_network)
            else -> stringResource(R.string.settings_phone_remote_open, url.orEmpty())
        },
        checked = enabled,
        onCheckedChange = viewModel::setEnabled,
        modifier = modifier,
    )
}
