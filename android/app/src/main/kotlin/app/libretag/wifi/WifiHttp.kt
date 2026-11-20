package app.libretag.wifi

import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * A proxy for the Wi-Fi lookup (later Tor's SOCKS port). The address stays unresolved, so with
 * SOCKS the proxy resolves the service's host name and the phone makes no DNS query of its own.
 */
data class ProxyConfig(val type: Proxy.Type, val host: String, val port: Int) {
    fun toProxy(): Proxy = Proxy(type, InetSocketAddress.createUnresolved(host, port))

    companion object {
        /** Orbot / Tor's default SOCKS port on the phone */
        fun torSocks(host: String = "127.0.0.1", port: Int = 9050) = ProxyConfig(Proxy.Type.SOCKS, host, port)
    }
}

/** Builds the one HTTP client the Wi-Fi lookup uses ([WifiLookupClient] takes it as a parameter). */
object WifiHttp {
    const val CALL_TIMEOUT_S = 20L

    /** null: a direct connection (the system's proxy settings, as before) */
    fun client(proxy: ProxyConfig? = null, callTimeoutS: Long = CALL_TIMEOUT_S): OkHttpClient =
        OkHttpClient.Builder()
            .callTimeout(callTimeoutS, TimeUnit.SECONDS)
            .apply { if (proxy != null) proxy(proxy.toProxy()) }
            .build()
}
