package com.follow.clash.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.ProxyInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.RemoteException
import android.util.Log
import androidx.core.content.getSystemService
import com.follow.clash.common.AccessControlMode
import com.follow.clash.common.GlobalState
import com.follow.clash.core.Core
import com.follow.clash.service.models.VpnOptions
import com.follow.clash.service.models.getIpv4RouteAddress
import com.follow.clash.service.models.getIpv6RouteAddress
import com.follow.clash.service.models.toCIDR
import com.follow.clash.service.modules.NetworkObserveModule
import com.follow.clash.service.modules.NotificationModule
import com.follow.clash.service.modules.SuspendModule
import com.follow.clash.service.modules.moduleLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.net.InetSocketAddress
import android.net.VpnService as SystemVpnService

class VpnService : SystemVpnService(), IBaseService,
    CoroutineScope by CoroutineScope(Dispatchers.Default) {

    private val self: VpnService
        get() = this

    private val networkModule = NetworkObserveModule(self)

    private val loader = moduleLoader {
        install(networkModule)
        install(NotificationModule(self))
        install(SuspendModule(self))
    }

    // VPN lifecycle recovery
    private var screenReceiver: BroadcastReceiver? = null
    private val recoveryHandler = Handler(Looper.getMainLooper())
    private var recoveryRunnable: Runnable? = null
    private var armRunnable: Runnable? = null
    @Volatile
    private var isRecovering = false
    @Volatile
    private var lastRecoveryTime: Long = 0L
    // Idempotency guard: repeated start() calls (app-side state desync,
    // user retrying the start button) must not re-establish the VPN and
    // tear down the current network. Each establish() replaces the previous
    // VPN network agent - that is what caused the 2026-10-19 storm where 4
    // rebuilds in 2 minutes pushed system_server to 155% CPU.
    @Volatile
    private var isStarted = false
    // Network-callback arming: right after registerNetworkCallback, the system
    // enumerates existing networks and fires onAvailable for each. Without this
    // guard that initial enumeration triggers a pointless restartTun ~5s after
    // every service start. (This restores the recoveryArmed guard that the
    // NetworkObserveModule refactor dropped.)
    @Volatile
    private var networkCallbackArmed = false
    // Quiet window after each restartTun: the VPN teardown/re-establish itself
    // flaps connectivity and re-triggers network callbacks (positive feedback).
    // Suppress recovery requests for NETWORK_QUIET_MS after a restart begins.
    @Volatile
    private var networkQuietUntil: Long = 0L

    override fun onCreate() {
        super.onCreate()
        handleCreate()
    }

    override fun onDestroy() {
        unregisterScreenReceiver()
        cancelPendingRecovery()
        cancelArm()
        networkModule.onNetworkChanged = null
        handleDestroy()
        super.onDestroy()
    }

    private val connectivity by lazy {
        getSystemService<ConnectivityManager>()
    }
    private val uidPageNameMap = mutableMapOf<Int, String>()

    private fun resolverProcess(
        protocol: Int,
        source: InetSocketAddress,
        target: InetSocketAddress,
        uid: Int,
    ): String {
        val nextUid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            connectivity?.getConnectionOwnerUid(protocol, source, target) ?: -1
        } else {
            uid
        }
        if (nextUid == -1) {
            return ""
        }
        if (!uidPageNameMap.containsKey(nextUid)) {
            uidPageNameMap[nextUid] = this.packageManager?.getPackagesForUid(nextUid)?.first() ?: ""
        }
        return uidPageNameMap[nextUid] ?: ""
    }

    val VpnOptions.address
        get(): String = buildString {
            append(IPV4_ADDRESS)
            if (ipv6) {
                append(",")
                append(IPV6_ADDRESS)
            }
        }

    val VpnOptions.dns
        get(): String {
            if (dnsHijacking) {
                return NET_ANY
            }
            return buildString {
                append(DNS)
                if (ipv6) {
                    append(",")
                    append(DNS6)
                }
            }
        }


    override fun onLowMemory() {
        Core.forceGC()
        super.onLowMemory()
    }

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): VpnService = this@VpnService

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            try {
                val isSuccess = super.onTransact(code, data, reply, flags)
                if (!isSuccess) {
                    GlobalState.log("VpnService disconnected")
                    handleDestroy()
                }
                return isSuccess
            } catch (e: RemoteException) {
                GlobalState.log("VpnService onTransact $e")
                return false
            }
        }
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    private fun handleStart(options: VpnOptions) {
        val fd = with(Builder()) {
            val cidr = IPV4_ADDRESS.toCIDR()
            addAddress(cidr.address, cidr.prefixLength)
            Log.d(
                "addAddress", "address: ${cidr.address} prefixLength:${cidr.prefixLength}"
            )
            val routeAddress = options.getIpv4RouteAddress()
            if (routeAddress.isNotEmpty()) {
                try {
                    routeAddress.forEach { i ->
                        Log.d(
                            "addRoute4", "address: ${i.address} prefixLength:${i.prefixLength}"
                        )
                        addRoute(i.address, i.prefixLength)
                    }
                } catch (_: Exception) {
                    addRoute(NET_ANY, 0)
                }
            } else {
                addRoute(NET_ANY, 0)
            }
            if (options.ipv6) {
                try {
                    val cidr = IPV6_ADDRESS.toCIDR()
                    Log.d(
                        "addAddress6", "address: ${cidr.address} prefixLength:${cidr.prefixLength}"
                    )
                    addAddress(cidr.address, cidr.prefixLength)
                } catch (_: Exception) {
                    Log.d(
                        "addAddress6", "IPv6 is not supported."
                    )
                }

                try {
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
                            addRoute(NET_ANY6, 0)
                        }
                    } else {
                        addRoute(NET_ANY6, 0)
                    }
                } catch (_: Exception) {
                    addRoute(NET_ANY6, 0)
                }
            }
            addDnsServer(DNS)
            if (options.ipv6) {
                addDnsServer(DNS6)
            }
            setMtu(9000)
            options.accessControlProps.let { accessControl ->
                if (accessControl.enable) {
                    when (accessControl.mode) {
                        AccessControlMode.ACCEPT_SELECTED -> {
                            (accessControl.acceptList + packageName).forEach {
                                addAllowedApplication(it)
                            }
                        }

                        AccessControlMode.REJECT_SELECTED -> {
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
            if (options.allowBypass) {
                allowBypass()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && options.systemProxy) {
                GlobalState.log("Open http proxy")
                setHttpProxy(
                    ProxyInfo.buildDirectProxy(
                        "127.0.0.1", options.port, options.bypassDomain
                    )
                )
            }
            establish()?.detachFd()
                ?: throw NullPointerException("Establish VPN rejected by system")
        }
        Core.startTun(
            fd,
            protect = this::protect,
            resolverProcess = this::resolverProcess,
            options.stack,
            options.address,
            options.dns
        )
        Log.i("vpn_lifecycle", "tun established: fd=$fd ipv6=${options.ipv6} stack=${options.stack}")
    }

    override fun start() {
        if (isStarted) {
            // Repeated start command while the service is already running:
            // ignore. handleStart() calls establish(), which tears down the
            // current VPN network and creates a new one - exactly the storm
            // amplifier we must avoid.
            Log.i("vpn_lifecycle", "start() ignored (already started)")
            return
        }
        isStarted = true
        try {
            networkModule.onNetworkChanged = ::scheduleVpnRecovery
            loader.load()
            State.options?.let {
                handleStart(it)
            }
            registerScreenReceiver()
            armNetworkCallback()
        } catch (_: Exception) {
            stop()
        }
    }

    override fun stop() {
        Log.i("vpn_lifecycle", "tun stopping")
        isStarted = false
        unregisterScreenReceiver()
        cancelPendingRecovery()
        cancelArm()
        networkModule.onNetworkChanged = null
        loader.cancel()
        Core.stopTun()
        stopSelf()
    }

    companion object {
        private const val IPV4_ADDRESS = "172.19.0.1/30"
        private const val IPV6_ADDRESS = "fdfe:dcba:9876::1/126"
        private const val DNS = "172.19.0.2"
        private const val DNS6 = "fdfe:dcba:9876::2"
        private const val NET_ANY = "0.0.0.0"
        private const val NET_ANY6 = "::"

        private const val RECOVERY_DEBOUNCE_MS = 5000L
        private const val RECOVERY_COOLDOWN_MS = 300000L  // 5 min
        private const val RESTART_STOP_DELAY_MS = 1000L   // 1s between stop and start
        // Arming delay: ignore the NetworkCallback's initial network enumeration
        // that fires right after registerNetworkCallback.
        private const val NETWORK_ARM_DELAY_MS = 15000L
        // Quiet window after restartTun begins: the restart itself flaps
        // connectivity (old network torn down, new one established) which
        // re-triggers network callbacks - without this guard the recovery loop
        // feeds itself.
        private const val NETWORK_QUIET_MS = 60000L
    }

    // ==================== VPN lifecycle recovery ====================

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_ON) {
                    Log.i("vpn_lifecycle", "SCREEN_ON detected")
                    GlobalState.log("[VPN] screen on detected, scheduling recovery")
                    scheduleVpnRecovery("screen_on")
                }
            }
        }
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
    }

    private fun unregisterScreenReceiver() {
        screenReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        screenReceiver = null
    }

    private fun scheduleVpnRecovery(reason: String) {
        if (isRecovering) {
            Log.i("vpn_lifecycle", "recovery ignored (already running): reason=$reason")
            return
        }
        if (!networkCallbackArmed) {
            // Initial enumeration or still within the post-start arming delay.
            Log.i("vpn_lifecycle", "recovery ignored (not armed): reason=$reason")
            return
        }
        val now = System.currentTimeMillis()
        if (now < networkQuietUntil) {
            Log.i(
                "vpn_lifecycle",
                "recovery ignored (quiet window): reason=$reason remaining=${networkQuietUntil - now}ms"
            )
            return
        }
        if (now - lastRecoveryTime < RECOVERY_COOLDOWN_MS) {
            Log.i("vpn_lifecycle", "recovery ignored (cooldown): reason=$reason remaining=${RECOVERY_COOLDOWN_MS - (now - lastRecoveryTime)}ms")
            return
        }
        cancelPendingRecovery()
        val runnable = Runnable {
            if (isRecovering) {
                Log.i("vpn_lifecycle", "recovery skipped (already running at fire time)")
                return@Runnable
            }
            restartTun(reason)
        }
        recoveryRunnable = runnable
        recoveryHandler.postDelayed(runnable, RECOVERY_DEBOUNCE_MS)
        Log.i("vpn_lifecycle", "recovery scheduled: reason=$reason delay=${RECOVERY_DEBOUNCE_MS}ms")
    }

    private fun cancelPendingRecovery() {
        recoveryRunnable?.let { recoveryHandler.removeCallbacks(it) }
        recoveryRunnable = null
    }

    /**
     * Delay the point where network-change events may trigger recovery, so the
     * NetworkCallback's initial enumeration (fired at register time for every
     * already-existing network) is ignored.
     */
    private fun armNetworkCallback() {
        cancelArm()
        networkCallbackArmed = false
        val runnable = Runnable {
            networkCallbackArmed = true
            Log.i("vpn_lifecycle", "network callback armed")
        }
        armRunnable = runnable
        recoveryHandler.postDelayed(runnable, NETWORK_ARM_DELAY_MS)
        Log.i("vpn_lifecycle", "network callback arming in ${NETWORK_ARM_DELAY_MS}ms")
    }

    private fun cancelArm() {
        armRunnable?.let { recoveryHandler.removeCallbacks(it) }
        armRunnable = null
        networkCallbackArmed = false
    }

    /**
     * Restart TUN without killing the process.
     * 1. Core.stopTun() — release old tun fd
     * 2. Wait 1s — let old fd fully released
     * 3. handleStart(options) — new VpnService.Builder().establish() + Core.startTun(new fd)
     *
     * isRecovering stays true during the entire stop→delay→start cycle,
     * blocking NetworkCallback self-trigger from restartTun establishment.
     */
    private fun restartTun(reason: String) {
        if (isRecovering) {
            Log.i("vpn_lifecycle", "restartTun skipped (already recovering)")
            return
        }
        val options = State.options
        if (options == null) {
            Log.w("vpn_lifecycle", "restartTun skipped (no options)")
            return
        }
        isRecovering = true
        lastRecoveryTime = System.currentTimeMillis()
        // The tearDown/re-establish below flips connectivity (old VPN network
        // disconnected, new one connected) which re-triggers network callbacks;
        // suppress any recovery request during this window to break the loop.
        networkQuietUntil = System.currentTimeMillis() + NETWORK_QUIET_MS
        Log.i("vpn_lifecycle", "restartTun begin: reason=$reason")
        GlobalState.log("[VPN] restart begin: $reason")

        try {
            Log.i("vpn_lifecycle", "restartTun: stopping current tun")
            Core.stopTun()

            recoveryHandler.postDelayed({
                try {
                    Log.i("vpn_lifecycle", "restartTun: re-establishing tun")
                    handleStart(options)
                    Log.i("vpn_lifecycle", "restartTun: done")
                    GlobalState.log("[VPN] restart success: $reason")
                } catch (e: Exception) {
                    Log.e("vpn_lifecycle", "restartTun re-establish failed: ${e.message}")
                    GlobalState.log("[VPN] restart failed: ${e.message}")
                } finally {
                    isRecovering = false
                }
            }, RESTART_STOP_DELAY_MS)
        } catch (e: Exception) {
            Log.e("vpn_lifecycle", "restartTun stop failed: ${e.message}")
            GlobalState.log("[VPN] restart stop failed: ${e.message}")
            isRecovering = false
        }
    }
}