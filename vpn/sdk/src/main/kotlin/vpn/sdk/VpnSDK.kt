package vpn.sdk

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import vpn.tunnel.VpnTunnelState
import vpn.tunnel.VpnStatistics
import vpn.network.NetworkResult
import vpn.network.VpnNetworkFactory
import vpn.tunnel.TunnelFactory

/**
 * Единая точка входа для работы с VPN.
 *
 * Как юзать:
 * 1. [setup] — инициализация (один раз, при старте приложения).
 * 2. [updateConfig] — фоновое обновление конфига.
 * 3. [toggleConnection] — подключение / отключение VPN.
 * 4. [addStateListener] / [removeStateListener] — callback-наблюдение (Java).
 * 5. [fetchAppUpdate] - проверка доступности обновления
 */
object VpnSDK {

    private const val TAG = "VpnSDK"

    fun interface StateListener {
        fun onStateChanged(state: VpnTunnelState)
    }

    fun interface AppUpdateCallback {
        fun onResult(updateInfo: AppUpdateResult?)
    }

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Unhandled coroutine exception", throwable)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    private val listeners = mutableSetOf<StateListener>()

    @Volatile
    private var isInitialized = false

    @JvmStatic
    val tunnelStateFlow: StateFlow<VpnTunnelState>
        get() {
            checkInitialized()
            return TunnelFactory.getTunnelManager().tunnelState
        }

    @JvmStatic
    fun getTunnelState(): VpnTunnelState {
        checkInitialized()
        return TunnelFactory.getTunnelManager().tunnelState.value
    }

    @JvmStatic
    fun addStateListener(listener: StateListener) {
        synchronized(listeners) { listeners.add(listener) }
    }

    @JvmStatic
    fun removeStateListener(listener: StateListener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    @Synchronized
    @JvmStatic
    @JvmOverloads
    fun setup(context: Context, debug: Boolean = false) {
        if (isInitialized) {
            Log.d(TAG, "Already initialized")
            return
        }
        val appContext = context.applicationContext
        VpnNetworkFactory.setup(appContext, debug)
        TunnelFactory.setup(appContext)
        isInitialized = true
        Log.d(TAG, "Initialized")

        TunnelFactory.getTunnelManager().tunnelState
            .onEach { state -> notifyListeners(state) }
            .flowOn(Dispatchers.Main.immediate)
            .launchIn(scope)
    }

    @JvmStatic
    fun updateConfig() {
        checkInitialized()
        scope.launch {
            Log.d(TAG, "Fetching remote config…")
            when (val result = VpnNetworkFactory.getAppConfigRepository().fetchRemoteConfig()) {
                is NetworkResult.Success -> Log.d(TAG, "Config updated")
                is NetworkResult.Error   -> Log.e(TAG, "Config error: ${result.message}")
                is NetworkResult.Failure -> Log.e(TAG, "Config failure, code=${result.code}")
            }
        }
    }

    @JvmStatic
    fun fetchAppUpdate(callback: AppUpdateCallback) {
        checkInitialized()
        scope.launch {
            Log.d(TAG, "Fetching app update info…")
            when (val result = VpnNetworkFactory.getAppUpdateRepository().fetchUpdateInfo()) {
                is NetworkResult.Success -> {
                    val info = result.data
                    Log.d(TAG, "App update info fetched: v${info.version}")
                    callback.onResult(
                        AppUpdateResult(
                            version = info.version,
                            versionCode = info.versionCode,
                            fileUrl = info.fileUrl,
                            changelog = info.changelog,
                            isRequired = info.isRequired,
                        )
                    )
                }
                is NetworkResult.Error -> {
                    Log.e(TAG, "App update fetch error: ${result.message}")
                    callback.onResult(null)
                }
                is NetworkResult.Failure -> {
                    Log.e(TAG, "App update fetch failure, code=${result.code}")
                    callback.onResult(null)
                }
            }
        }
    }

    @JvmStatic
    fun toggleConnection() {
        checkInitialized()
        when (tunnelStateFlow.value) {
            VpnTunnelState.DOWN -> scope.launch { connect() }
            VpnTunnelState.UP -> disconnect()
            VpnTunnelState.CONNECTING -> Unit
        }
    }

    @JvmStatic
    fun getStatistics(): VpnStatistics {
        checkInitialized()
        return TunnelFactory.getTunnelManager().getStatistics()
    }

    @JvmStatic
    fun getDebugInfo(): String {
        checkInitialized()
        val deviceId = VpnNetworkFactory.getDeviceIdRepository().getDeviceId()
        val clientIP = TunnelFactory.lastClientIP
        val serverIP = TunnelFactory.lastServerIP
        return buildString {
            append("deviceId = $deviceId")
            clientIP?.let { append("\nclientIP = $it") }
            serverIP?.let { append("\nserverIP = $it") }
        }
    }

    private suspend fun connect() {
        Log.d(TAG, "Connecting…")
        TunnelFactory.getTunnelManager().setState(VpnTunnelState.CONNECTING, null)

        val keyResult = VpnNetworkFactory.getVpnApi().getAnonymousKey()
        if (keyResult !is NetworkResult.Success) {
            Log.w(TAG, "Failed to get anonymous key: $keyResult")
            TunnelFactory.getTunnelManager().setState(VpnTunnelState.DOWN, null)
            return
        }

        TunnelFactory.connectWithRawConfig(keyResult.data)
    }

    @JvmStatic
    fun disconnect() {
        if (!isInitialized) return
        Log.d(TAG, "Disconnecting…")
        TunnelFactory.disconnect()
    }

    private fun notifyListeners(state: VpnTunnelState) {
        val snapshot: List<StateListener>
        synchronized(listeners) { snapshot = listeners.toList() }
        snapshot.forEach { it.onStateChanged(state) }
    }

    private fun checkInitialized() {
        check(isInitialized) { "VpnSDK is not initialized. Call VpnSDK.setup(context) first." }
    }
}
