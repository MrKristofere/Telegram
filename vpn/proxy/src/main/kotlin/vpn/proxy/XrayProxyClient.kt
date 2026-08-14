package vpn.proxy

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Main-process client of [XrayProxyService] (the ":xray" process).
 *
 * The main process must never touch libXray / [XrayProxy] methods directly —
 * that would load a second Go runtime next to the AmneziaWG GoBackend, which
 * is the exact conflict the process split exists to avoid. Constants
 * ([XrayProxy.SOCKS_PORT], host) are safe: they don't load native code.
 *
 * The binding is requested in [setup] and held forever (BIND_AUTO_CREATE
 * keeps the ":xray" process alive alongside ours); a failed bindService is
 * retried from [awaitConnected]. If the system still kills the process,
 * Android restarts it and re-delivers the binder; [onServiceConnected] then
 * re-issues start() with the last config so MTProto's SOCKS reconnects find
 * a live listener again.
 *
 * Threading: all control calls are synchronous binder transactions — cheap
 * once bound. start() also runs xray config parsing on the remote side
 * (~100–300 ms), so prefer background threads where possible. start()/stop()
 * are serialized on this object's monitor so a reconnect replay can never
 * interleave with (or resurrect a config over) a concurrent start/stop.
 * [awaitConnected] must never be called on the main thread: the
 * ServiceConnection callbacks are delivered there and would deadlock.
 */
object XrayProxyClient {

    /** Outcome of [start], so callers can tell a bad config from a bad transport. */
    enum class StartResult {
        /** xray is running with the given config (or was already running). */
        STARTED,
        /** The remote side evaluated the config and refused it — the config itself is bad. */
        REJECTED,
        /** The config was never evaluated: binding down or the IPC itself failed. */
        UNAVAILABLE,
    }

    private const val TAG = "XrayProxyClient"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var appContext: Context

    @Volatile
    private var service: IXrayProxyService? = null

    @Volatile
    private var connectedLatch = CountDownLatch(1)

    /** True once bindService() has been accepted; a false return leaves it unset for retry. */
    @Volatile
    private var bindRequested = false

    /** Last config successfully passed to start(); replayed on process restart. */
    @Volatile
    private var lastStartedConfig: String? = null

    /** Local copy of the failure reason for when the remote side is unreachable. */
    @Volatile
    var lastError: String? = null
        private set

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.d(TAG, "Connected to :xray process")
            service = IXrayProxyService.Stub.asInterface(binder)
            connectedLatch.countDown()
            if (lastStartedConfig != null) {
                // The :xray process was killed and restarted — bring the
                // listener back so existing proxy settings stay valid.
                scope.launch { replayLastConfig() }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, ":xray process died; awaiting automatic rebind")
            service = null
            connectedLatch = CountDownLatch(1)
            // BIND_AUTO_CREATE makes the system restart the service and call
            // onServiceConnected again — no manual rebind needed.
        }
    }

    @Synchronized
    fun setup(context: Context) {
        if (this::appContext.isInitialized) return
        appContext = context.applicationContext
        bindIfNeeded()
    }

    @Synchronized
    private fun bindIfNeeded() {
        if (bindRequested || !this::appContext.isInitialized) return
        val bound = appContext.bindService(
            Intent(appContext, XrayProxyService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        Log.d(TAG, "bindService(:xray) -> $bound")
        if (bound) {
            bindRequested = true
        } else {
            // Per the bindService contract a false return still needs an
            // unbind to release the request; do it so the next attempt
            // (from awaitConnected) starts clean.
            try {
                appContext.unbindService(connection)
            } catch (e: Throwable) {
                Log.e(TAG, "unbindService after failed bind", e)
            }
            lastError = "bindService returned false"
        }
    }

    fun isConnected(): Boolean = service != null

    /**
     * Blocks until the binding is up (or [timeoutMs] elapses). Background
     * threads only — the connection callback arrives on the main thread.
     */
    fun awaitConnected(timeoutMs: Long): Boolean {
        if (service != null) return true
        bindIfNeeded() // retry a bindService that failed earlier
        return try {
            connectedLatch.await(timeoutMs, TimeUnit.MILLISECONDS) && service != null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /** Returns true if xray is running (false when the binding is down). */
    fun isRunning(): Boolean {
        val svc = service ?: return false
        return try {
            svc.isRunning
        } catch (e: Throwable) {
            Log.e(TAG, "isRunning IPC failed", e)
            false
        }
    }

    /**
     * Starts xray with [configJson]. Returns [StartResult.UNAVAILABLE] when
     * the binding is not up yet — callers on background threads may
     * [awaitConnected] first. Only [StartResult.REJECTED] means the config
     * itself was tried and refused.
     */
    @Synchronized
    fun start(configJson: String): StartResult {
        val svc = service
        if (svc == null) {
            lastError = ":xray process not bound yet"
            Log.w(TAG, "start skipped: not bound")
            return StartResult.UNAVAILABLE
        }
        return try {
            if (svc.start(configJson)) {
                lastStartedConfig = configJson
                lastError = null
                StartResult.STARTED
            } else {
                lastError = try { svc.lastError } catch (e: Throwable) { "IPC: ${e.message}" }
                StartResult.REJECTED
            }
        } catch (e: Throwable) {
            lastError = "IPC: ${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "start IPC failed", e)
            StartResult.UNAVAILABLE
        }
    }

    @Synchronized
    fun stop() {
        lastStartedConfig = null
        val svc = service ?: return
        try {
            svc.stop()
        } catch (e: Throwable) {
            Log.e(TAG, "stop IPC failed", e)
        }
    }

    /**
     * Re-issues start() after a reconnect. Reads [lastStartedConfig] under
     * the same monitor as [start]/[stop], so a stop() or a fresh start() that
     * raced with the reconnect always wins — the replay can neither resurrect
     * a deliberately stopped proxy nor roll a newer config back to a stale one.
     */
    @Synchronized
    private fun replayLastConfig() {
        val config = lastStartedConfig ?: return
        val result = start(config)
        Log.d(TAG, "Replayed xray start after reconnect: $result")
    }
}
