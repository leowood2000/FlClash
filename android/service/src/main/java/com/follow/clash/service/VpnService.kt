package com.follow.clash.service

import android.content.Intent
import android.net.ConnectivityManager
import android.net.ProxyInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
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
import com.follow.clash.service.models.getIpv4RouteExcludeAddress
import com.follow.clash.service.models.getIpv6RouteExcludeAddress
import com.follow.clash.service.models.toCIDR
import com.follow.clash.service.models.CIDR
import com.follow.clash.service.modules.NetworkObserveModule
import com.follow.clash.service.modules.NotificationModule
import com.follow.clash.service.modules.SuspendModule
import com.follow.clash.service.modules.moduleLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.net.InetAddress
import java.net.InetSocketAddress
import java.math.BigInteger
import android.net.VpnService as SystemVpnService

class VpnService : SystemVpnService(), IBaseService,
    CoroutineScope by CoroutineScope(Dispatchers.Default) {

    private val self: VpnService
        get() = this

    private val loader = moduleLoader {
        install(NetworkObserveModule(self))
        install(NotificationModule(self))
        install(SuspendModule(self))
    }

    override fun onCreate() {
        super.onCreate()
        handleCreate()
    }

    override fun onDestroy() {
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
        GlobalState.log("VpnService handleStart options: routeExcludeAddress=${options.routeExcludeAddress}, routeAddress=${options.routeAddress}, ipv6=${options.ipv6}")
        val fd = with(Builder()) {
            val cidr = IPV4_ADDRESS.toCIDR()
            addAddress(cidr.address, cidr.prefixLength)
            Log.d(
                "addAddress", "address: ${cidr.address} prefixLength:${cidr.prefixLength}"
            )
            val routeAddress = options.getIpv4RouteAddress()
            val excludeAddress4 = options.getIpv4RouteExcludeAddress()
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
            } else if (excludeAddress4.isNotEmpty()) {
                // Exclude specific CIDRs from 0.0.0.0/0
                val excluded = subtractCidr(BigInteger.ZERO, 0, 32, excludeAddress4)
                excluded.forEach { i ->
                    Log.d(
                        "addRoute4_excluded", "address: ${i.address} prefixLength:${i.prefixLength}"
                    )
                    addRoute(i.address, i.prefixLength)
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
                    val excludeAddress6 = options.getIpv6RouteExcludeAddress()
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
                    } else if (excludeAddress6.isNotEmpty()) {
                        // Exclude specific CIDRs from ::/0
                        val excluded = subtractCidr(BigInteger.ZERO, 0, 128, excludeAddress6)
                        excluded.forEach { i ->
                            Log.d(
                                "addRoute6_excluded", "address: ${i.address} prefixLength:${i.prefixLength}"
                            )
                            addRoute(i.address, i.prefixLength)
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
    }

    override fun start() {
        try {
            loader.load()
            State.options?.let {
                handleStart(it)
            }
        } catch (_: Exception) {
            stop()
        }
    }

    override fun stop() {
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

        /**
         * Subtract a set of CIDRs from a supernet (base, prefix).
         * Returns the list of CIDRs that cover (supernet minus all excluded CIDRs).
         *
         * Algorithm: recursively split the supernet in half. If one half fully
         * contains an exclude CIDR (or is itself excluded), drop it; otherwise
         * recurse. If neither half intersects any exclude, keep it as-is.
         */
        private fun subtractCidr(
            base: BigInteger,
            prefix: Int,
            maxPrefix: Int,
            excludes: List<CIDR>,
        ): List<CIDR> {
            val results = mutableListOf<CIDR>()
            subtractCidrRecursive(base, prefix, maxPrefix, excludes, results)
            return results
        }

        private fun subtractCidrRecursive(
            base: BigInteger,
            prefix: Int,
            maxPrefix: Int,
            excludes: List<CIDR>,
            results: MutableList<CIDR>,
        ) {
            if (prefix > maxPrefix) return

            val size = BigInteger.ONE.shiftLeft(maxPrefix - prefix)
            val rangeStart = base.shiftLeft(maxPrefix - prefix)
            val rangeEnd = rangeStart.add(size).subtract(BigInteger.ONE)

            // Check if this CIDR is fully covered by any exclude
            for (ex in excludes) {
                val exBytes = ex.address.address
                val exBase = bytesToBigInt(exBytes)
                val exPrefix = ex.prefixLength
                val exSize = BigInteger.ONE.shiftLeft(maxPrefix - exPrefix)
                val exStart = exBase.shiftLeft(maxPrefix - exPrefix)
                    .and(BigInteger.ONE.shiftLeft(maxPrefix).subtract(BigInteger.ONE))
                val exEnd = exStart.add(exSize).subtract(BigInteger.ONE)

                // Normalize base for current prefix
                val mask = BigInteger.ONE.shiftLeft(maxPrefix).subtract(BigInteger.ONE)
                val normBase = base.and(mask)
                val normStart = normBase.shiftLeft(maxPrefix - prefix).and(mask)
                val normEnd = normStart.add(size).subtract(BigInteger.ONE)

                if (normStart >= exStart && normEnd <= exEnd) {
                    // This CIDR is fully excluded
                    return
                }
            }

            // Check if any exclude intersects this CIDR
            var hasIntersection = false
            for (ex in excludes) {
                val exBytes = ex.address.address
                val exBase = bytesToBigInt(exBytes)
                val exPrefix = ex.prefixLength
                val mask = BigInteger.ONE.shiftLeft(maxPrefix).subtract(BigInteger.ONE)
                val exStart = exBase.shiftLeft(maxPrefix - exPrefix).and(mask)
                val exSize = BigInteger.ONE.shiftLeft(maxPrefix - exPrefix)
                val exEnd = exStart.add(exSize).subtract(BigInteger.ONE)

                val normBase = base.and(mask)
                val normStart = normBase.shiftLeft(maxPrefix - prefix).and(mask)
                val normEnd = normStart.add(size).subtract(BigInteger.ONE)

                if (normStart <= exEnd && exStart <= normEnd) {
                    hasIntersection = true
                    break
                }
            }

            if (!hasIntersection || prefix == maxPrefix) {
                // No intersection, or can't split further — keep this CIDR
                val addr = bigIntToBytes(base.shiftLeft(maxPrefix - prefix), maxPrefix)
                results.add(CIDR(InetAddress.getByAddress(addr), prefix))
                return
            }

            // Split into two halves
            val halfSize = BigInteger.ONE.shiftLeft(maxPrefix - prefix - 1)
            val leftBase = base
            val rightBase = base.add(halfSize)
            subtractCidrRecursive(leftBase, prefix + 1, maxPrefix, excludes, results)
            subtractCidrRecursive(rightBase, prefix + 1, maxPrefix, excludes, results)
        }

        private fun bytesToBigInt(bytes: ByteArray): BigInteger {
            return BigInteger(1, bytes)
        }

        private fun bigIntToBytes(value: BigInteger, byteLen: Int): ByteArray {
            val raw = value.toByteArray()
            return when {
                raw.size == byteLen -> raw
                raw.size < byteLen -> ByteArray(byteLen - raw.size) + raw
                else -> raw.copyOfRange(raw.size - byteLen, raw.size)
            }
        }
    }
}