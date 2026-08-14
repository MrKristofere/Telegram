// IXrayProxyService.aidl
package vpn.proxy;

/**
 * Control interface of the xray-core host service running in the isolated
 * ":xray" process. All methods are synchronous binder calls — cheap once the
 * binding is established. The SOCKS5 data plane (127.0.0.1:17808) is plain
 * localhost TCP and does not go through this interface.
 */
interface IXrayProxyService {
    /** Starts xray with the given config JSON. Returns true on success or if already running. */
    boolean start(String configJson);

    void stop();

    boolean isRunning();

    /** Last start() failure reason, or null. */
    String getLastError();
}
