package com.follow.clash.plugins

import android.os.Process

/**
 * P0: root 辅助网络配置（仅在有 root 的盒子上生效，无 root 自动跳过）。
 *
 * 解决 Android 5.1 上两类系统限制：
 * 1. App 的 DNS 不走 VpnService 隧道（netd 不认 addDnsServer）；
 * 2. YouTube TV 等 App 不认全局系统代理。
 *
 * 方案：核心启动后（服务模式），用 iptables 把 App 的
 *   TCP 80/443/5228/1935 重定向到 redir-port 7891，
 *   UDP 53 重定向到 Clash DNS 8853（fake-ip），
 * 并清掉系统解析器缓存，使所有 App（含不认代理的）都走 Clash。
 */
object RootNetHelper {
    private const val REDIR_PORT = 7891
    private const val DNS_PORT = 8853

    fun isAvailable(): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out.contains("uid=0")
        } catch (_: Exception) {
            false
        }
    }

    fun setup(full: Boolean) {
        if (!isAvailable()) return
        val uid = Process.myUid()
        val script = buildString {
            if (full) {
                appendLine("iptables -t nat -N FLCLASH 2>/dev/null")
                appendLine("iptables -t nat -F FLCLASH")
                // 服务模式：DNS 优先全部走 Clash（fake-ip）
                appendLine("iptables -t nat -A FLCLASH -p udp --dport 53 -j REDIRECT --to-ports $DNS_PORT")
                appendLine("iptables -t nat -A FLCLASH -p tcp --dport 53 -j REDIRECT --to-ports $DNS_PORT")
                // 本地/内网直连
                for (net in listOf(
                    "0.0.0.0/8", "10.0.0.0/8", "127.0.0.0/8", "169.254.0.0/16",
                    "172.16.0.0/12", "192.168.0.0/16", "224.0.0.0/4", "240.0.0.0/4",
                )) {
                    appendLine("iptables -t nat -A FLCLASH -d $net -j RETURN")
                }
                // 放行自身/adbd/root，避免环路
                for (u in listOf(uid, 2000, 0)) {
                    appendLine("iptables -t nat -A FLCLASH -m owner --uid-owner $u -j RETURN")
                }
                appendLine("iptables -t nat -A FLCLASH -p tcp -m multiport --dports 80,443,5228,1935 -j REDIRECT --to-ports $REDIR_PORT")
                appendLine("iptables -t nat -A OUTPUT -j FLCLASH")
            } else {
                // VPN 模式：TCP 已由 VpnService 捕获，只补 DNS 重定向
                // Android 5.1 compatibility: some vendor ROMs keep resolving through the
                // physical network even when VPN LinkProperties contains the configured DNS.
                appendLine("iptables -t nat -N FLCLASH_DNS 2>/dev/null")
                appendLine("iptables -t nat -F FLCLASH_DNS")
                appendLine("iptables -t nat -A FLCLASH_DNS -d 127.0.0.0/8 -j RETURN")
                for (u in listOf(uid, 0)) {
                    appendLine("iptables -t nat -A FLCLASH_DNS -m owner --uid-owner $u -j RETURN")
                }
                appendLine("iptables -t nat -A FLCLASH_DNS -p udp --dport 53 -j REDIRECT --to-ports $DNS_PORT")
                appendLine("iptables -t nat -A FLCLASH_DNS -p tcp --dport 53 -j REDIRECT --to-ports $DNS_PORT")
                appendLine("iptables -t nat -D OUTPUT -j FLCLASH_DNS 2>/dev/null")
                appendLine("iptables -t nat -I OUTPUT 1 -j FLCLASH_DNS")
            }
            // 清 DNS 缓存，避免继续使用被污染的路由器解析结果
            appendLine("ndc resolver flushnet 100 2>/dev/null")
            appendLine("ndc resolver flushdefaultif 2>/dev/null")
        }
        exec(script)
    }

    fun teardown() {
        if (!isAvailable()) return
        exec(
            "iptables -t nat -D OUTPUT -j FLCLASH 2>/dev/null;" +
                "iptables -t nat -F FLCLASH 2>/dev/null;" +
                "iptables -t nat -X FLCLASH 2>/dev/null;" +
                "iptables -t nat -D OUTPUT -j FLCLASH_DNS 2>/dev/null;" +
                "iptables -t nat -F FLCLASH_DNS 2>/dev/null;" +
                "iptables -t nat -X FLCLASH_DNS 2>/dev/null",
        )
    }

    private fun exec(script: String) {
        try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", script))
            p.inputStream.bufferedReader().readText()
            p.errorStream.bufferedReader().readText()
            p.waitFor()
        } catch (_: Exception) {
        }
    }
}
