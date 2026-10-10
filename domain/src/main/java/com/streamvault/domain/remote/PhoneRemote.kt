package com.streamvault.domain.remote

import kotlinx.coroutines.flow.StateFlow

/** A page served on the local network that drives the app like the TV remote. */
interface PhoneRemote {
    val enabled: StateFlow<Boolean>

    /** The address to open on the phone; null while off or while the TV has no LAN address. */
    val url: StateFlow<String?>

    fun setEnabled(enabled: Boolean)
}
