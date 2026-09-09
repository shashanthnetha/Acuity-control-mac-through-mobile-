package com.macky.client.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

private const val TAG = "MdnsDiscoveryManager"
private const val SERVICE_TYPE = "_acuity._tcp."

data class DiscoveredServer(
    val name: String,
    val host: String,
    val port: Int,
    val roomCode: String,
    val version: String = "1.0",
    val lastSeen: Long = System.currentTimeMillis()
) {
    val serverUrl: String
        get() = "http://$host:$port"

    val wsUrl: String
        get() = "ws://$host:$port"
}

/**
 * Browses the local Wi-Fi network using NsdManager for Mac agent instances advertising _acuity._tcp.
 * Acquires a MulticastLock to ensure multicast DNS packets are not dropped by Android OS power saving.
 */
class MdnsDiscoveryManager(
    private val context: Context,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private val nsdManager: NsdManager? =
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private var multicastLock: WifiManager.MulticastLock? = null

    private val _discoveredServers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val discoveredServers: StateFlow<List<DiscoveredServer>> = _discoveredServers.asStateFlow()

    private val serverMap = ConcurrentHashMap<String, DiscoveredServer>()
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var isDiscovering = false

    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    private var isResolving = false

    @Synchronized
    fun startDiscovery() {
        if (isDiscovering || nsdManager == null) return

        try {
            // Acquire MulticastLock to receive mDNS broadcast packets
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifiManager?.createMulticastLock("AcuityMdnsLock")?.apply {
                setReferenceCounted(true)
                acquire()
            }
            Log.d(TAG, "Acquired Wi-Fi MulticastLock for mDNS discovery")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire MulticastLock: ${e.message}")
        }

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.e(TAG, "Discovery start failed: error code $errorCode")
                isDiscovering = false
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.e(TAG, "Discovery stop failed: error code $errorCode")
                isDiscovering = false
            }

            override fun onDiscoveryStarted(serviceType: String?) {
                Log.i(TAG, "mDNS service discovery started for $serviceType")
                isDiscovering = true
            }

            override fun onDiscoveryStopped(serviceType: String?) {
                Log.i(TAG, "mDNS service discovery stopped")
                isDiscovering = false
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "mDNS Service found: ${serviceInfo.serviceName} (${serviceInfo.serviceType})")
                // Queue for serialized resolution (NsdManager only permits one concurrent resolve call)
                resolveQueue.offer(serviceInfo)
                processResolveQueue()
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "mDNS Service lost: ${serviceInfo.serviceName}")
                serverMap.remove(serviceInfo.serviceName)
                _discoveredServers.value = serverMap.values.toList().sortedByDescending { it.lastSeen }
            }
        }

        try {
            nsdManager.discoverServices(
                SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error starting NsdManager discovery: ${e.message}", e)
        }
    }

    @Synchronized
    private fun processResolveQueue() {
        if (isResolving) return
        val nextService = resolveQueue.poll() ?: return
        isResolving = true

        try {
            nsdManager?.resolveService(nextService, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    Log.w(TAG, "Failed to resolve service ${serviceInfo?.serviceName}: error $errorCode")
                    isResolving = false
                    processResolveQueue()
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val hostAddress = serviceInfo.host?.hostAddress
                    val port = serviceInfo.port
                    val name = serviceInfo.serviceName

                    if (hostAddress != null && port > 0) {
                        val roomCode = serviceInfo.attributes["room"]?.let {
                            String(it, Charsets.UTF_8).trim()
                        } ?: ""

                        val version = serviceInfo.attributes["version"]?.let {
                            String(it, Charsets.UTF_8).trim()
                        } ?: "1.0"

                        val server = DiscoveredServer(
                            name = name,
                            host = hostAddress,
                            port = port,
                            roomCode = roomCode,
                            version = version,
                            lastSeen = System.currentTimeMillis()
                        )

                        Log.i(TAG, "Resolved Acuity agent: '$name' at $hostAddress:$port (Room: $roomCode)")
                        serverMap[name] = server
                        _discoveredServers.value = serverMap.values.toList().sortedByDescending { it.lastSeen }
                    }

                    isResolving = false
                    processResolveQueue()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Exception calling resolveService: ${e.message}", e)
            isResolving = false
            processResolveQueue()
        }
    }

    @Synchronized
    fun stopDiscovery() {
        if (!isDiscovering) return

        try {
            discoveryListener?.let {
                nsdManager?.stopServiceDiscovery(it)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping service discovery: ${e.message}")
        } finally {
            discoveryListener = null
            isDiscovering = false
            resolveQueue.clear()
            isResolving = false
        }

        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
                Log.d(TAG, "Released Wi-Fi MulticastLock")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing MulticastLock: ${e.message}")
        }
        multicastLock = null
    }
}
