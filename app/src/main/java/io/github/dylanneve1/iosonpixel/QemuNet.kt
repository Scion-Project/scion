package io.github.dylanneve1.iosonpixel

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import java.io.File
import java.net.Inet6Address
import java.net.InetAddress

/**
 * The QEMU backend's Network setting at run time ([VmSettings.qemuNetwork], docs/NETWORKING.md):
 * the phone's DNS servers for slirp, and the status line.
 *
 * libslirp normally reads /etc/resolv.conf, which Android doesn't have. Our build reads the file
 * named by [ENV_RESOLV_CONF] instead (#82), so the app writes the default network's DNS servers
 * (from [LinkProperties]) to [AppPaths.resolvConf] and rewrites it when they change. slirp notices
 * a new file by itself; QEMU needs no restart.
 */
object QemuNet {
    /** QEMU's environment: the resolv.conf-format file libslirp reads (set before QEMU starts, never changed). */
    const val ENV_RESOLV_CONF = "SLIRP_RESOLV_CONF"

    /** Inferno's usb-uplink-type=net reports the guest's NCM link state with these lines. */
    private const val CONNECTED = "usb-net-uplink: guest reports network connected"
    private const val DISCONNECTED = "usb-net-uplink: guest reports network disconnected"

    /** slirp's default network: 10.0.2.2 is the gateway, 10.0.2.3 the DNS forwarder, guests start at .15. */
    private val GUEST_ADDR = Regex("""\b10\.0\.2\.(\d{1,3})\b""")

    /**
     * resolv.conf text for [servers], or null when there is none: an empty file would make slirp
     * fall back to 127.0.0.1, which has no resolver on Android, so the caller deletes it instead.
     * A link-local IPv6 server gets the interface ([ifname]) as its scope.
     */
    fun resolvConf(servers: List<InetAddress>, ifname: String?): String? {
        if (servers.isEmpty()) return null
        return servers.joinToString("") { a ->
            val host = a.hostAddress!!.substringBefore('%')
            val scope = if (a is Inet6Address && a.isLinkLocalAddress && !ifname.isNullOrEmpty()) "%$ifname" else ""
            "nameserver $host$scope\n"
        }
    }

    /** The guest's address in `info usernet` output: the first 10.0.2.x that isn't slirp's own. */
    fun guestAddress(usernet: String): String? =
        GUEST_ADDR.findAll(usernet).map { it.value to it.groupValues[1].toInt() }
            .firstOrNull { (_, host) -> host !in setOf(0, 2, 3, 255) && host < 256 }?.first

    /** The status line's text for a QEMU run; see [VmState.network]. */
    fun statusText(on: Boolean, restore: Boolean, connected: Boolean, address: String?): String = when {
        !on -> "Network: off"
        restore -> "Network: off for restore boots"
        address != null -> "Network: guest has $address"
        connected -> "Network: guest connected, waiting for its address"
        else -> "Network: waiting for guest"
    }

    /** Writes [text] to [file] atomically (a temp file renamed over it), or deletes [file] when [text] is null. */
    fun writeAtomically(file: File, text: String?) {
        if (text == null) {
            file.delete()
            return
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            error("cannot replace ${file.path}")
        }
    }

    /**
     * Follows the phone's default network while a VM with the Network setting runs, keeping
     * [AppPaths.resolvConf] in step with its DNS servers. [stop] when the VM is gone.
     */
    class Resolver(ctx: Context, private val paths: AppPaths, private val log: (String) -> Unit) {
        private val cm = ctx.getSystemService(ConnectivityManager::class.java)
        private var last: String? = null

        private val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = update(lp)
            override fun onLost(network: Network) = update(null)
        }

        /** Writes the current servers now, then follows changes. */
        fun start() {
            update(cm.activeNetwork?.let { cm.getLinkProperties(it) })
            runCatching { cm.registerDefaultNetworkCallback(callback) }
                .onFailure { log("network: cannot follow the phone's network: $it") }
        }

        fun stop() {
            runCatching { cm.unregisterNetworkCallback(callback) }
            paths.resolvConf.delete()
        }

        @Synchronized private fun update(lp: LinkProperties?) {
            val text = lp?.let { resolvConf(it.dnsServers, it.interfaceName) }
            if (text == last && (text == null || paths.resolvConf.isFile)) return
            last = text
            runCatching { writeAtomically(paths.resolvConf, text) }
                .onSuccess {
                    log(
                        if (text == null) "network: the phone has no DNS server right now; guest DNS fails until it has one"
                        else "network: DNS through " + text.lines().filter { it.isNotBlank() }.joinToString(", ") { it.removePrefix("nameserver ") },
                    )
                }
                .onFailure { log("network: cannot write ${paths.resolvConf.path}: $it") }
        }
    }

    /**
     * A QEMU run with the guest network: watches the output for the guest's link, asks slirp
     * (`info usernet` on the monitor) for the guest's address, and keeps the status line.
     */
    class Watch(
        private val inner: VmBackend.Instance,
        private val paths: AppPaths,
        private val resolver: Resolver,
    ) : VmBackend.Instance by inner {
        @Volatile private var connected = false
        @Volatile private var address: String? = null
        @Volatile private var done = false

        init {
            publish()
        }

        override fun pump(onLine: (String) -> Unit): Int {
            val poller = Thread({ poll() }, "vm-net-status").apply { isDaemon = true; start() }
            try {
                return inner.pump { line ->
                    when {
                        CONNECTED in line -> { connected = true; publish() }
                        DISCONNECTED in line -> { connected = false; address = null; publish() }
                    }
                    onLine(line)
                }
            } finally {
                done = true
                poller.interrupt()
                resolver.stop()
                VmState.network(null)
            }
        }

        /** While the link is up and the address unknown, ask slirp every few seconds. */
        private fun poll() {
            try {
                while (!done) {
                    Thread.sleep(POLL_MS)
                    if (!connected || address != null) continue
                    val found = Monitor(paths).query("info usernet")?.let { guestAddress(it) } ?: continue
                    address = found
                    publish()
                }
            } catch (_: InterruptedException) {
            }
        }

        private fun publish() {
            if (!done) VmState.network(statusText(on = true, restore = false, connected = connected, address = address))
        }

        private companion object {
            const val POLL_MS = 3000L
        }
    }
}
