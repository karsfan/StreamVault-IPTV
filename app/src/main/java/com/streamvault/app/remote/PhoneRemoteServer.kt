package com.streamvault.app.remote

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.text.format.Formatter
import android.util.Log
import android.view.KeyEvent
import com.streamvault.domain.remote.PhoneRemote
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.lang.ref.WeakReference
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Serves the phone remote page and turns its taps into key events for the visible activity,
 * so every screen reacts exactly as it does to the real remote, held keys included.
 *
 * The port is fixed so the phone's bookmark keeps working; the random path token keeps other
 * devices on the network from driving the TV by guessing the address.
 */
@Singleton
class PhoneRemoteServer @Inject constructor(
    @param:ApplicationContext private val context: Context
) : PhoneRemote {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    private val _url = MutableStateFlow<String?>(null)
    override val url: StateFlow<String?> = _url.asStateFlow()

    private val token: String by lazy {
        prefs.getString(KEY_TOKEN, null) ?: newToken().also { prefs.edit().putString(KEY_TOKEN, it).apply() }
    }

    @Volatile private var activity: WeakReference<Activity>? = null
    private var serverSocket: ServerSocket? = null
    private var heldKeyCode: Int? = null
    private var heldDownTime = 0L
    private var repeatJob: Job? = null

    /** Called by the activity while it is on screen: key events go to it and nowhere else. */
    fun attach(activity: Activity) {
        this.activity = WeakReference(activity)
        if (_enabled.value) start()
    }

    fun detach(activity: Activity) {
        if (this.activity?.get() === activity) this.activity = null
    }

    override fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _enabled.value = enabled
        if (enabled) start() else stop()
    }

    @Synchronized
    private fun start() {
        val running = serverSocket?.takeUnless { it.isClosed }
        // The LAN address can change (DHCP, Wi-Fi to Ethernet), so it is read again each time.
        _url.value = lanIpv4Address()?.let { host -> "http://$host:$PORT/$token" }
        if (running != null) return
        val socket = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT), BACKLOG)
            }
        }.getOrElse { error ->
            Log.w(TAG, "Phone remote could not listen on port $PORT", error)
            _url.value = null
            return
        }
        serverSocket = socket
        scope.launch { acceptLoop(socket) }
    }

    @Synchronized
    private fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        _url.value = null
        scope.launch { releaseHeldKey() }
    }

    private suspend fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            client.tcpNoDelay = true
            scope.launch {
                runCatching { handle(client) }
                    .onFailure { Log.w(TAG, "Phone remote request failed", it) }
                runCatching { client.close() }
            }
        }
    }

    private suspend fun handle(client: Socket) {
        client.soTimeout = READ_TIMEOUT_MS
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
        val requestLine = reader.readLine() ?: return
        while (true) {
            val header = reader.readLine() ?: break
            if (header.isEmpty()) break
        }
        val target = requestLine.split(' ').getOrNull(1) ?: return respond(client.getOutputStream(), 400)
        val path = target.substringBefore('?').trim('/')
        val query = target.substringAfter('?', "").split('&')
            .associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val segments = path.split('/')
        if (segments.firstOrNull() != token) return respond(client.getOutputStream(), 404)
        val out = client.getOutputStream()
        when (segments.getOrNull(1)) {
            null -> respond(out, 200, "text/html; charset=utf-8", page())
            "ping" -> respond(out, if (visibleActivity() != null) 204 else 409)
            "down" -> respond(out, keyCode(query["k"])?.let { press(it) } ?: 400)
            "up" -> {
                releaseHeldKey()
                respond(out, 204)
            }
            "vol" -> respond(out, adjustVolume(query["d"]))
            else -> respond(out, 404)
        }
    }

    /** Key down now; while the finger stays on the button the key repeats like a held remote key. */
    private suspend fun press(keyCode: Int): Int {
        val target = visibleActivity() ?: return 409
        releaseHeldKey()
        val downTime = SystemClock.uptimeMillis()
        synchronized(this) {
            heldKeyCode = keyCode
            heldDownTime = downTime
        }
        dispatch(target, KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0))
        repeatJob = scope.launch {
            delay(REPEAT_START_MS)
            var repeat = 1
            val giveUpAt = downTime + MAX_HOLD_MS
            while (SystemClock.uptimeMillis() < giveUpAt) {
                val now = SystemClock.uptimeMillis()
                val flags = if (repeat == 1) KeyEvent.FLAG_LONG_PRESS else 0
                dispatch(target, KeyEvent(downTime, now, KeyEvent.ACTION_DOWN, keyCode, repeat, 0, -1, 0, flags))
                repeat++
                delay(REPEAT_EVERY_MS)
            }
            // A lost "up" (phone locked mid-press) must not leave the key held forever.
            releaseHeldKey()
        }
        return 204
    }

    private suspend fun releaseHeldKey() {
        val (keyCode, downTime) = synchronized(this) {
            val code = heldKeyCode ?: return
            heldKeyCode = null
            code to heldDownTime
        }
        repeatJob?.takeUnless { it === kotlinx.coroutines.currentCoroutineContext()[Job] }?.cancel()
        repeatJob = null
        val target = visibleActivity() ?: return
        dispatch(target, KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0))
    }

    private suspend fun dispatch(target: Activity, event: KeyEvent) {
        withContext(Dispatchers.Main) {
            if (!target.isFinishing && !target.isDestroyed) target.dispatchKeyEvent(event)
        }
    }

    private fun visibleActivity(): Activity? =
        activity?.get()?.takeUnless { it.isFinishing || it.isDestroyed }

    private fun adjustVolume(direction: String?): Int {
        val adjust = when (direction) {
            "up" -> AudioManager.ADJUST_RAISE
            "down" -> AudioManager.ADJUST_LOWER
            "mute" -> AudioManager.ADJUST_TOGGLE_MUTE
            else -> return 400
        }
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return 409
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, adjust, AudioManager.FLAG_SHOW_UI)
        return 204
    }

    private fun page(): String =
        context.assets.open(PAGE_ASSET).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }

    private fun respond(out: OutputStream, status: Int, contentType: String = "text/plain", body: String = "") {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            404 -> "Not Found"
            else -> "Conflict"
        }
        out.write(
            ("HTTP/1.1 $status $reason\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(StandardCharsets.UTF_8)
        )
        out.write(bytes)
        out.flush()
    }

    private fun lanIpv4Address(): String? {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val capabilities = connectivity?.activeNetwork?.let(connectivity::getNetworkCapabilities)
        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
            val ip = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                ?.connectionInfo?.ipAddress?.takeIf { it != 0 }
            if (ip != null) return Formatter.formatIpAddress(ip)
        }
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<Inet4Address>()
                .mapNotNull { it.hostAddress }
                .firstOrNull { !it.startsWith("127.") }
        }.getOrNull()
    }

    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES).also(SecureRandom()::nextBytes)
        return bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    companion object {
        private const val TAG = "PhoneRemote"
        private const val PREFS = "phone_remote"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_TOKEN = "token"
        private const val PAGE_ASSET = "phone-remote.html"
        const val PORT = 8765
        private const val BACKLOG = 16
        private const val TOKEN_BYTES = 4
        private const val READ_TIMEOUT_MS = 5_000
        private const val REPEAT_START_MS = 400L
        private const val REPEAT_EVERY_MS = 70L
        private const val MAX_HOLD_MS = 8_000L

        fun keyCode(name: String?): Int? = when (name) {
            "up" -> KeyEvent.KEYCODE_DPAD_UP
            "down" -> KeyEvent.KEYCODE_DPAD_DOWN
            "left" -> KeyEvent.KEYCODE_DPAD_LEFT
            "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "ok" -> KeyEvent.KEYCODE_DPAD_CENTER
            "back" -> KeyEvent.KEYCODE_BACK
            "menu" -> KeyEvent.KEYCODE_MENU
            "info" -> KeyEvent.KEYCODE_INFO
            "guide" -> KeyEvent.KEYCODE_GUIDE
            "chup" -> KeyEvent.KEYCODE_CHANNEL_UP
            "chdown" -> KeyEvent.KEYCODE_CHANNEL_DOWN
            "last" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "red" -> KeyEvent.KEYCODE_PROG_RED
            "green" -> KeyEvent.KEYCODE_PROG_GREEN
            "yellow" -> KeyEvent.KEYCODE_PROG_YELLOW
            "blue" -> KeyEvent.KEYCODE_PROG_BLUE
            else -> name?.singleOrNull()?.takeIf(Char::isDigit)?.let { KeyEvent.KEYCODE_0 + (it - '0') }
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PhoneRemoteModule {
    @Binds
    abstract fun bindPhoneRemote(impl: PhoneRemoteServer): PhoneRemote
}
