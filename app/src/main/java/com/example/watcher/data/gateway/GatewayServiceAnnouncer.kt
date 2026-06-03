package com.example.watcher.data.gateway

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * Registers the gateway HTTP service via mDNS (DNS-SD) so LAN clients
 * can discover it without knowing the IP address.
 *
 * Service type: _watcher._tcp
 * Discoverable via:
 *   macOS:  dns-sd -B _watcher._tcp
 *   Linux:  avahi-browse _watcher._tcp
 *   Python: zeroconf library
 */
class GatewayServiceAnnouncer(context: Context) {

    companion object {
        private const val TAG = "GatewayAnnouncer"
        private const val SERVICE_TYPE = "_watcher._tcp."
        private const val SERVICE_NAME = "Watcher-Gateway"
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null

    fun register(port: Int, attributes: Map<String, String> = emptyMap()) {
        unregister()
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            setPort(port)
            attributes.forEach { (key, value) ->
                if (key.isNotBlank() && value.isNotBlank()) {
                    setAttribute(key, value)
                }
            }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.d(TAG, "mDNS registered: ${info.serviceName} on port $port")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS registration failed: error=$errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.d(TAG, "mDNS unregistered: ${info.serviceName}")
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS unregistration failed: error=$errorCode")
            }
        }
        registrationListener = listener
        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register mDNS service", e)
        }
    }

    fun unregister() {
        registrationListener?.let { listener ->
            try {
                nsdManager.unregisterService(listener)
            } catch (_: Exception) { }
            registrationListener = null
        }
    }
}
