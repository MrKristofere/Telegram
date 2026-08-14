package vpn.proxy

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * Hosts xray-core (libXray, a gomobile AAR with its own embedded Go runtime)
 * in the dedicated ":xray" process — see android:process in the manifest.
 *
 * The isolation exists because two Go runtimes (libXray here and the AmneziaWG
 * GoBackend in the main process) conflict when loaded into one process. Only
 * this service may touch [XrayProxy] / libXray symbols; the main process talks
 * to it through [IXrayProxyService] (control plane) and connects to the SOCKS5
 * listener on 127.0.0.1 (data plane), which works fine across processes.
 *
 * The service is bound-only (no onStartCommand lifecycle): it lives while the
 * main process holds the binding and is restarted by a rebind after the system
 * kills the process. State recovery is the client's job — [XrayProxyClient]
 * re-issues start() with the last config on reconnect.
 */
class XrayProxyService : Service() {

    private val binder = object : IXrayProxyService.Stub() {
        override fun start(configJson: String): Boolean = XrayProxy.start(configJson)

        override fun stop() = XrayProxy.stop()

        override fun isRunning(): Boolean = XrayProxy.isRunning()

        override fun getLastError(): String? = XrayProxy.lastError
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "onBind")
        return binder
    }

    override fun onDestroy() {
        XrayProxy.stop()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "XrayProxyService"
    }
}
