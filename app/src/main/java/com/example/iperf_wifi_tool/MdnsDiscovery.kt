package com.example.iperf_wifi_tool

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import java.util.ArrayDeque

data class IperfService(val name: String, val host: String, val port: Int)

class MdnsDiscovery(
    context: Context,
    private val onServicesChanged: (List<IperfService>) -> Unit,
    private val onStatusChanged: (String) -> Unit,
) {
    companion object {
        const val SERVICE_TYPE = "_iperf3._tcp."
    }

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null
    private val services = linkedMapOf<String, IperfService>()
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var started = false

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = onStatusChanged("正在查找 RK3399…")

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (serviceInfo.serviceType.equals(SERVICE_TYPE, ignoreCase = true)) {
                resolveQueue.add(serviceInfo)
                resolveNext()
            }
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            services.remove(serviceInfo.serviceName)
            publish()
        }

        override fun onDiscoveryStopped(serviceType: String) = onStatusChanged("发现已停止")
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            started = false
            onStatusChanged("mDNS 启动失败（$errorCode）")
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            onStatusChanged("mDNS 停止失败（$errorCode）")
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving || resolveQueue.isEmpty()) return
        resolving = true
        val item = resolveQueue.removeFirst()
        nsd.resolveService(item, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                resolving = false
                resolveNext()
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val address = serviceInfo.host?.hostAddress
                if (!address.isNullOrBlank()) {
                    services[serviceInfo.serviceName] = IperfService(
                        serviceInfo.serviceName,
                        address.substringBefore('%'),
                        serviceInfo.port.takeIf { it > 0 } ?: 5201,
                    )
                    publish()
                }
                resolving = false
                resolveNext()
            }
        })
    }

    private fun publish() {
        val sorted = services.values.sortedWith(
            compareByDescending<IperfService> { it.name.contains("rk3399", ignoreCase = true) }
                .thenBy { it.name }
        )
        onServicesChanged(sorted)
        onStatusChanged(if (sorted.isEmpty()) "未发现服务" else "发现 ${sorted.size} 台服务")
    }

    fun start() {
        if (started) return
        started = true
        services.clear()
        multicastLock = wifi.createMulticastLock("iperf-mdns").apply {
            setReferenceCounted(false)
            acquire()
        }
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            started = false
            onStatusChanged("mDNS 无法启动：${e.message}")
        }
    }

    fun refresh() {
        stop()
        start()
    }

    fun stop() {
        if (started) {
            try { nsd.stopServiceDiscovery(discoveryListener) } catch (_: Exception) {}
        }
        started = false
        resolveQueue.clear()
        resolving = false
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }
}
