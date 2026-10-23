package com.antirecording.shield

import kotlin.math.abs

/** Groups Wi-Fi BSSIDs that belong to one physical radio (hidden / guest / virtual SSIDs). Shared by the radar and the room check. */
object RadioCluster {
    /** 48-bit MAC with the locally-administered bit of the first byte cleared. */
    fun macBits(bssid: String): Long? = bssid.replace(":", "").takeIf { it.length == 12 }?.toLongOrNull(16)?.and(0x020000000000L.inv())

    /** Same band + channel, signal within 6 dB, MACs within 8 bits (locally-administered bit ignored). Nearest first; named SSIDs before <hidden> inside a group. */
    fun cluster(list: List<WifiScanner.Ap>, dist: (WifiScanner.Ap) -> Double?): List<List<WifiScanner.Ap>> {
        val clusters = ArrayList<ArrayList<WifiScanner.Ap>>()
        for (a in list.sortedByDescending { if (it.hasRssi()) it.rssi() else -200 }) {
            val ma = macBits(a.bssid)
            val home = if (ma == null || !a.hasRssi()) null else clusters.firstOrNull { c ->
                val s = c[0]; val ms = macBits(s.bssid)
                ms != null && s.hasRssi() && s.band() == a.band() && s.channel() == a.channel() &&
                    abs(s.rssi() - a.rssi()) <= 6 && java.lang.Long.bitCount(ma xor ms) <= 8
            }
            if (home != null) home.add(a) else clusters.add(arrayListOf(a))
        }
        fun d(x: WifiScanner.Ap) = dist(x) ?: Double.MAX_VALUE
        return clusters.map { c -> c.sortedWith(compareBy({ it.ssid.isBlank() }, { d(it) })) }.sortedBy { c -> c.minOf { d(it) } }
    }

    /** Stable id of a cluster (smallest base MAC in it). */
    fun key(g: List<WifiScanner.Ap>): String = g.minOf { macBits(it.bssid)?.toString(16) ?: it.bssid } + "/" + g[0].channel()
}
