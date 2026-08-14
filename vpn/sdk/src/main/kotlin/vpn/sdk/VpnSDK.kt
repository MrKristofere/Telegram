package vpn.sdk

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import vpn.tunnel.VpnTunnelState
import vpn.tunnel.VpnStatistics
import vpn.network.NetworkResult
import vpn.network.VpnNetworkFactory
import vpn.tunnel.TunnelFactory
import vpn.proxy.XrayProxy
import vpn.proxy.XrayProxyClient

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

    /** How long background callers wait for the ":xray" process binding. */
    private const val XRAY_BIND_TIMEOUT_MS = 5_000L

    fun interface StateListener {
        fun onStateChanged(state: VpnTunnelState)
    }

    fun interface AppUpdateCallback {
        fun onResult(updateInfo: AppUpdateResult?)
    }

    fun interface RegisterCallback {
        /** Invoked on Main thread after [registerOrAuth] finishes (with all retries). */
        fun onResult(success: Boolean)
    }

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Unhandled coroutine exception", throwable)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    private val listeners = mutableSetOf<StateListener>()
    private val registerMutex = Mutex()
    private val connectInProgress = AtomicBoolean(false)

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
        // Kick off the binding to the ":xray" process early — by the time
        // anything needs the proxy the connection is usually already up.
        XrayProxyClient.setup(appContext)
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
            VpnTunnelState.DOWN -> {
                // CONNECTING lands in the state flow asynchronously (two
                // coroutine hops away), so two quick toggles can both observe
                // DOWN; the flag turns the second into a no-op instead of a
                // concurrent connect().
                if (!connectInProgress.compareAndSet(false, true)) return
                scope.launch {
                    try {
                        connect()
                    } finally {
                        connectInProgress.set(false)
                    }
                }
            }
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
            append("\n--- xray proxy ---")
            append("\nxray bound = ${XrayProxyClient.isConnected()}")
            val xrayRunning = XrayProxyClient.isRunning()
            append("\nxray running = $xrayRunning")
            if (xrayRunning) {
                append("\nxray addr = ${XrayProxy.socksHost}:${XrayProxy.socksPort}")
            }
            XrayProxyClient.lastError?.let { append("\nxray error = $it") }
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

    // ---- Proxy (VLESS+Reality via libxray) ----

    fun interface ProxyStartCallback {
        /** Invoked on Main thread. */
        fun onResult(success: Boolean)
    }

    /**
     * Starts the local SOCKS5 proxy (xray-core, in the ":xray" process) using
     * the cached server-issued config (from the last successful
     * /auth/register).
     *
     * Returns `true` if xray is now running (or was already running). Returns
     * `false` when no cached config exists yet (kick off [registerOrAuth]) or
     * when the binder connection to the ":xray" process isn't up — for the
     * latter case prefer [startProxyAsync], which waits for the binding.
     *
     * If a cached config exists but libxray rejects it (corrupt cache, schema
     * drift, expired credentials in the JSON, etc.) the cache is cleared and
     * `false` is returned, so the next register cycle will replace it. A
     * transport failure (binding down, ":xray" died mid-call) also returns
     * `false` but keeps the cache — the config was never evaluated.
     */
    @JvmStatic
    fun startProxy(): Boolean {
        checkInitialized()
        val cached = VpnNetworkFactory.getRegistrationRepository().getCachedConfigJson()
        if (cached == null) {
            Log.d(TAG, "startProxy: no cached config; xray not started")
            return false
        }
        Log.d(TAG, "Starting xray with cached server config (size=${cached.length})")
        return when (XrayProxyClient.start(cached)) {
            XrayProxyClient.StartResult.STARTED -> true
            XrayProxyClient.StartResult.REJECTED -> {
                Log.w(TAG, "Cached xray config rejected, clearing cache: ${XrayProxyClient.lastError}")
                VpnNetworkFactory.getRegistrationRepository().clearCachedConfig()
                false
            }
            XrayProxyClient.StartResult.UNAVAILABLE -> {
                Log.w(TAG, "startProxy: :xray unavailable, keeping cache: ${XrayProxyClient.lastError}")
                false
            }
        }
    }

    /**
     * Like [startProxy], but tolerates a not-yet-established binding to the
     * ":xray" process (e.g. right after cold start): waits for the connection
     * on a background thread, then starts xray. [callback] fires on Main.
     */
    @JvmStatic
    fun startProxyAsync(callback: ProxyStartCallback? = null) {
        checkInitialized()
        scope.launch {
            val bound = XrayProxyClient.awaitConnected(XRAY_BIND_TIMEOUT_MS)
            if (!bound) {
                Log.e(TAG, "startProxyAsync: :xray binding not up after ${XRAY_BIND_TIMEOUT_MS}ms")
            }
            val success = bound && startProxy()
            withContext(Dispatchers.Main) { callback?.onResult(success) }
        }
    }

    /** True iff a server-issued xray config is cached locally. */
    @JvmStatic
    fun hasCachedXrayConfig(): Boolean {
        checkInitialized()
        return VpnNetworkFactory.getRegistrationRepository().getCachedConfigJson() != null
    }

    @JvmStatic
    fun stopProxy() {
        Log.d(TAG, "Stopping xray proxy…")
        XrayProxyClient.stop()
    }

    @JvmStatic
    fun isProxyRunning(): Boolean = XrayProxyClient.isRunning()

    /** Port of the local SOCKS5 proxy. Valid only when [isProxyRunning] returns true. */
    @JvmStatic
    fun getProxySocksPort(): Int = XrayProxy.socksPort

    @JvmStatic
    fun getProxySocksHost(): String = XrayProxy.socksHost

    @JvmStatic
    fun getProxyLastError(): String? = XrayProxyClient.lastError

    /**
     * Calls POST /auth/register against the backend, with [maxAttempts] tries
     * spaced by exponential backoff (1s, 2s, 4s, …). On success, caches the
     * returned xray config and v2rayKey, restarts the local proxy with the new
     * config, and invokes [callback] on the Main thread with `true`.
     *
     * On exhaustion of all attempts (or if xray fails to restart with the
     * new config), invokes [callback] with `false`. The caller is responsible
     * for any UI feedback. After `success=true` callers should also push the
     * new proxy address into ConnectionsManager via `setProxySettings(...)`
     * to apply it across active accounts at runtime.
     *
     * Concurrent invocations are serialised: while one attempt cycle is in
     * flight, additional calls are dropped silently (no callback is invoked).
     * This protects both the backend (no duplicate findOrCreate races) and
     * the local libxray native state (no concurrent stop/start).
     */
    @JvmStatic
    @JvmOverloads
    fun registerOrAuth(maxAttempts: Int = 3, callback: RegisterCallback? = null) {
        checkInitialized()
        scope.launch {
            if (!registerMutex.tryLock()) {
                Log.d(TAG, "registerOrAuth already in flight, dropping duplicate call")
                return@launch
            }
            try {
                val success = runRegisterWithRetries(maxAttempts)
                withContext(Dispatchers.Main) { callback?.onResult(success) }
            } finally {
                registerMutex.unlock()
            }
        }
    }

    private suspend fun runRegisterWithRetries(maxAttempts: Int): Boolean {
        var delayMs = 1000L
        var lastResult: NetworkResult<Unit>? = null
        for (attempt in 1..maxAttempts) {
            Log.d(TAG, "registerOrAuth attempt $attempt/$maxAttempts")
            val result = VpnNetworkFactory.getRegistrationRepository().register()
            lastResult = result
            if (result is NetworkResult.Success) {
                // API success means a fresh config is in cache; we still
                // need libxray to accept it. If the restart fails, the whole
                // operation is a failure — don't pretend otherwise to the
                // caller, who will then advance MTProto onto a dead proxy.
                return applyCachedConfigToXray()
            }
            if (attempt < maxAttempts) {
                delay(delayMs)
                delayMs *= 2
            }
        }
        Log.w(TAG, "registerOrAuth exhausted $maxAttempts attempts: $lastResult")
        return false
    }

    /** Runs on the IO scope (register cycle) — blocking on the binding is fine here. */
    private fun applyCachedConfigToXray(): Boolean {
        val configJson = VpnNetworkFactory.getRegistrationRepository().getCachedConfigJson()
        if (configJson == null) {
            Log.w(TAG, "applyCachedConfigToXray: no cached config (unexpected after success)")
            return false
        }
        if (!XrayProxyClient.awaitConnected(XRAY_BIND_TIMEOUT_MS)) {
            Log.w(TAG, "applyCachedConfigToXray: :xray binding not up after ${XRAY_BIND_TIMEOUT_MS}ms")
            return false
        }
        XrayProxyClient.stop()
        val result = XrayProxyClient.start(configJson)
        if (result != XrayProxyClient.StartResult.STARTED) {
            Log.w(TAG, "Failed to restart xray with new config ($result): ${XrayProxyClient.lastError}")
            return false
        }
        Log.d(TAG, "Xray restarted with fresh server config")
        return true
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
