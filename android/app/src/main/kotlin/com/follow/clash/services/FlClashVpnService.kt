package com.follow.clash.services

import android.annotation.SuppressLint
import android.content.Intent
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.util.Log
import androidx.core.app.NotificationCompat
import com.follow.clash.GlobalState
import com.follow.clash.extensions.getIpv4RouteAddress
import com.follow.clash.extensions.getIpv6RouteAddress
import com.follow.clash.extensions.toCIDR
import com.follow.clash.models.AccessControlMode
import com.follow.clash.models.VpnOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


class FlClashVpnService : VpnService(), BaseServiceInterface {
    private var establishedFd: Int? = null

    companion object {
        @Volatile
        var underlyingNetwork: android.net.Network? = null
    }

    override fun onCreate() {
        super.onCreate()
        GlobalState.initServiceEngine()
    }

    override fun start(options: VpnOptions): Int {
        // 幂等：避免重复 establish() 导致系统注册重复 VPN agent
        establishedFd?.let { return it }
        Log.i("FlClashVpn", "Before establish")
        return with(Builder()) {
            if (options.ipv4Address.isNotEmpty()) {
                val cidr = options.ipv4Address.toCIDR()
                addAddress(cidr.address, cidr.prefixLength)
                Log.d(
                    "addAddress",
                    "address: ${cidr.address} prefixLength:${cidr.prefixLength}"
                )
                val routeAddress = options.getIpv4RouteAddress()
                if (routeAddress.isNotEmpty()) {
                    try {
                        routeAddress.forEach { i ->
                            Log.d(
                                "addRoute4",
                                "address: ${i.address} prefixLength:${i.prefixLength}"
                            )
                            addRoute(i.address, i.prefixLength)
                        }
                    } catch (_: Exception) {
                        addRoute("0.0.0.0", 0)
                    }
                } else {
                    addRoute("0.0.0.0", 0)
                }
            } else {
                addRoute("0.0.0.0", 0)
            }
            try {
            if (options.ipv6Address.isNotEmpty()) {
                    val cidr = options.ipv6Address.toCIDR()
                    Log.d(
                        "addAddress6",
                        "address: ${cidr.address} prefixLength:${cidr.prefixLength}"
                    )
                    addAddress(cidr.address, cidr.prefixLength)
                    val routeAddress = options.getIpv6RouteAddress()
                    if (routeAddress.isNotEmpty()) {
                        try {
                            routeAddress.forEach { i ->
                                Log.d(
                                    "addRoute6",
                                    "address: ${i.address} prefixLength:${i.prefixLength}"
                                )
                                addRoute(i.address, i.prefixLength)
                            }
                        } catch (_: Exception) {
                            addRoute("::", 0)
                        }
                    } else {
                        addRoute("::", 0)
                    }
                }
            }catch (_:Exception){
                Log.d(
                    "addAddress6",
                    "IPv6 is not supported."
                )
            }
            // Android 5.1-8.1 上不声明底层网络，VPN 可能无法接管应用流量
            if (Build.VERSION.SDK_INT in 22..28) {
                val underlying = FlClashVpnService.underlyingNetwork
                if (underlying != null) {
                    try {
                        setUnderlyingNetworks(arrayOf(underlying))
                    } catch (_: Exception) {
                    }
                }
            }
            // Android 5.1 的 netd 不认私网 DNS（172.19.0.2），查询不会进隧道；
            // 改用公网 DNS（223.5.5.5 / 2400:3200::1），配合核心 any:53 劫持即可
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                addDnsServer("223.5.5.5")
                if (options.ipv6Address.isNotEmpty()) {
                    addDnsServer("2400:3200::1")
                }
            } else {
                addDnsServer(options.dnsServerAddress)
            }
            // Android 5.1 上 9000 MTU 容易出问题，用标准 1500
            setMtu(if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) 1500 else 9000)
            options.accessControl.let { accessControl ->
                if (accessControl.enable) {
                    when (accessControl.mode) {
                        AccessControlMode.acceptSelected -> {
                            (accessControl.acceptList + packageName).forEach {
                                addAllowedApplication(it)
                            }
                        }

                        AccessControlMode.rejectSelected -> {
                            (accessControl.rejectList - packageName).forEach {
                                addDisallowedApplication(it)
                            }
                        }
                    }
                }
            }
            setSession("FlClash")
            setBlocking(false)
            if (Build.VERSION.SDK_INT >= 29) {
                setMetered(false)
            }
            // 老系统上 allowBypass 可能导致流量不被捕获，禁用
            if (options.allowBypass && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                allowBypass()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && options.systemProxy) {
                setHttpProxy(
                    ProxyInfo.buildDirectProxy(
                        "127.0.0.1",
                        options.port,
                        options.bypassDomain
                    )
                )
            }
            establish()?.detachFd()
                ?: throw NullPointerException("Establish VPN rejected by system")
        }.also { establishedFd = it }
            .also { Log.i("FlClashVpn", "Established fd=$it") }
    }

    override fun stop() {
        establishedFd = null
        stopSelf()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private var cachedBuilder: NotificationCompat.Builder? = null

    private suspend fun notificationBuilder(): NotificationCompat.Builder {
        if (cachedBuilder == null) {
            cachedBuilder = createFlClashNotificationBuilder().await()
        }
        return cachedBuilder!!
    }

    @SuppressLint("ForegroundServiceType")
    override suspend fun startForeground(title: String, content: String) {
        startForeground(
            notificationBuilder()
                .setContentTitle(title)
                .setContentText(content).build()
        )
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        GlobalState.getCurrentVPNPlugin()?.requestGc()
    }

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): FlClashVpnService = this@FlClashVpnService

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            try {
                val isSuccess = super.onTransact(code, data, reply, flags)
                if (!isSuccess) {
                    CoroutineScope(Dispatchers.Main).launch {
                        GlobalState.getCurrentTilePlugin()?.handleStop()
                    }
                }
                return isSuccess
            } catch (e: RemoteException) {
                throw e
            }
        }
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stop()
        super.onDestroy()
    }
}
