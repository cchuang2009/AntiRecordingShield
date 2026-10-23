package com.antirecording.shield

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.TreeSet
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * LAN camera scan: looks for devices on the Wi-Fi network you are connected to that behave like IP cameras / recorders.
 *  - TCP connect to a few camera-related ports (RTSP 554/8554, HTTP 80/8080, 8000, 443, Dahua 37777, XM 34567 ...)
 *  - an unauthenticated RTSP OPTIONS request and an HTTP GET / to read the Server header and page title
 *  - ONVIF WS-Discovery probe (multicast UDP 3702)
 * It NEVER logs in, tries passwords, reads video or changes anything, scans at most one /24, and refuses non-private addresses.
 * Use it only on a network you own or are allowed to test.  A match is a HINT: routers, NAS and printers also open these ports.
 */
class LanScanner(
    private val ctx: Context,
    private val onProgress: (String) -> Unit,
    private val onDone: (List<Host>, String?) -> Unit
) {
    class Host(val ip: String) {
        val ports = TreeSet<Int>()
        var banner = ""
        var rtsp = false
        var onvif = false
        var score = 0
        val reasons = ArrayList<String>()
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var cancelled = false
    private var pool: ExecutorService? = null
    var running = false; private set

    private val ports = intArrayOf(554, 8554, 80, 8080, 8000, 443, 37777, 34567, 8899, 5000)
    private val camWords = Regex("hikvision|dahua|dvr|nvr|ipcam|ip camera|webcam|onvif|camera|uc-httpd|goahead|boa/|app-webs|dnvrs|netsurveillance|ezviz|reolink|amcrest|foscam|mjpg|cctv", RegexOption.IGNORE_CASE)

    private val probeXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><e:Envelope xmlns:e=\"http://www.w3.org/2003/05/soap-envelope\" " +
        "xmlns:w=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\" xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\" " +
        "xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\"><e:Header><w:MessageID>uuid:%s</w:MessageID>" +
        "<w:To e:mustUnderstand=\"true\">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>" +
        "<w:Action e:mustUnderstand=\"true\">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header>" +
        "<e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>"

    private fun isPrivate(b: ByteArray): Boolean {
        val a = b[0].toInt() and 0xFF; val c = b[1].toInt() and 0xFF
        return a == 10 || (a == 172 && c in 16..31) || (a == 192 && c == 168)
    }
    private fun toInt(b: ByteArray) = ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
    private fun toIp(v: Int) = "${(v ushr 24) and 0xFF}.${(v ushr 16) and 0xFF}.${(v ushr 8) and 0xFF}.${v and 0xFF}"

    /** @return an error message, or null when the scan has started. Results arrive through onDone (main thread). */
    fun start(): String? {
        if (running) return null
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        val net = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            ?: return L.t("Not connected to Wi-Fi. Connect to the network you want to check first.", "尚未連上 Wi-Fi。請先連上要檢查的網路。")
        val lp = cm.getLinkProperties(net) ?: return L.t("No network information available.", "無法取得網路資訊。")
        val la = lp.linkAddresses.firstOrNull { it.address is Inet4Address }
            ?: return L.t("This Wi-Fi has no IPv4 address.", "此 Wi-Fi 沒有 IPv4 位址。")
        val me = (la.address as Inet4Address).address
        if (!isPrivate(me)) return L.t("Refused: this is not a private network address.", "已拒絕：這不是私有網路位址。")
        val prefix = la.prefixLength.coerceIn(24, 30)             // never more than one /24
        val mask = -1 shl (32 - prefix)
        val self = toInt(me)
        val base = self and mask
        val count = (1 shl (32 - prefix)) - 2
        cancelled = false; running = true
        val p = Executors.newFixedThreadPool(48); pool = p
        val hosts = Collections.synchronizedList(ArrayList<Host>())
        val onvifIps = Collections.synchronizedSet(HashSet<String>())
        val done = AtomicInteger(0)
        val total = count
        Thread { onvifProbe(net, onvifIps) }.start()
        for (k in 1..count) {
            val ipInt = base + k
            if (ipInt == self) { done.incrementAndGet(); continue }
            p.execute {
                if (!cancelled) {
                    val h = Host(toIp(ipInt))
                    for (port in ports) { if (cancelled) break; open(net, h.ip, port, 350)?.let { s -> h.ports.add(port); try { s.close() } catch (_: Exception) {} } }
                    if (h.ports.isNotEmpty() && !cancelled) { grab(net, h); hosts.add(h) }
                }
                val n = done.incrementAndGet()
                if (n % 16 == 0 || n == total) main.post { if (running) onProgress(L.t("LAN scan: $n / $total addresses…", "區網掃描：$n / $total 個位址…")) }
            }
        }
        p.shutdown()
        Thread {
            try { p.awaitTermination(120, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
            val list = ArrayList<Host>(hosts)
            synchronized(onvifIps) { for (ip in onvifIps) { val h = list.firstOrNull { it.ip == ip } ?: Host(ip).also { list.add(it) }; h.onvif = true } }
            for (h in list) score(h)
            list.sortByDescending { it.score }
            main.post { running = false; onDone(list, if (cancelled) L.t("Scan cancelled.", "掃描已取消。") else null) }
        }.start()
        return null
    }

    fun cancel() { cancelled = true; pool?.shutdownNow() }

    private fun open(net: Network, ip: String, port: Int, timeoutMs: Int): Socket? = try {
        val s = net.socketFactory.createSocket(); s.connect(InetSocketAddress(ip, port), timeoutMs); s
    } catch (_: Exception) { null }

    private fun readSome(s: Socket): String = try {
        val b = ByteArray(2048); val n = s.getInputStream().read(b)
        if (n > 0) String(b, 0, n, Charsets.ISO_8859_1) else ""
    } catch (_: Exception) { "" }

    private fun grab(net: Network, h: Host) {
        for (p in intArrayOf(554, 8554)) if (p in h.ports) open(net, h.ip, p, 500)?.use { s ->
            try {
                s.soTimeout = 800
                s.getOutputStream().write("OPTIONS rtsp://${h.ip}:$p/ RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: HushShield\r\n\r\n".toByteArray())
                val r = readSome(s)
                if (r.startsWith("RTSP/")) { h.rtsp = true; Regex("(?im)^server:\\s*(.+)$").find(r)?.let { h.banner += " " + it.groupValues[1].trim() } }
            } catch (_: Exception) {}
        }
        for (p in intArrayOf(80, 8080)) if (p in h.ports) open(net, h.ip, p, 500)?.use { s ->
            try {
                s.soTimeout = 800
                s.getOutputStream().write("GET / HTTP/1.0\r\nHost: ${h.ip}\r\nUser-Agent: HushShield\r\nConnection: close\r\n\r\n".toByteArray())
                val r = readSome(s)
                Regex("(?im)^server:\\s*(.+)$").find(r)?.let { h.banner += " " + it.groupValues[1].trim() }
                Regex("(?is)<title>(.*?)</title>").find(r)?.let { h.banner += " " + it.groupValues[1].trim().take(60) }
            } catch (_: Exception) {}
        }
    }

    private fun score(h: Host) {
        if (h.rtsp) { h.score += 4; h.reasons.add(L.t("answers RTSP (video streaming protocol)", "回應 RTSP（影像串流協定）")) }
        else if (554 in h.ports || 8554 in h.ports) { h.score += 2; h.reasons.add(L.t("RTSP port open", "RTSP 連接埠開啟")) }
        if (37777 in h.ports) { h.score += 3; h.reasons.add(L.t("Dahua-style service port 37777", "Dahua 類服務埠 37777")) }
        if (34567 in h.ports) { h.score += 3; h.reasons.add(L.t("DVR-style service port 34567", "DVR 類服務埠 34567")) }
        if (8000 in h.ports && (80 in h.ports || 443 in h.ports)) { h.score += 1; h.reasons.add(L.t("port 8000 + web (Hikvision-like)", "8000 埠＋網頁（類 Hikvision）")) }
        if (camWords.containsMatchIn(h.banner)) { h.score += 3; h.reasons.add(L.t("web banner mentions camera/recorder:${h.banner.take(70)}", "網頁資訊提到攝影機／錄影機：${h.banner.take(70)}")) }
        if (h.onvif) { h.score += 4; h.reasons.add(L.t("answers ONVIF discovery", "回應 ONVIF 探索")) }
    }

    private fun onvifProbe(net: Network, found: MutableSet<String>) {
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
        val lock = try { wm?.createMulticastLock("hush-onvif")?.also { it.setReferenceCounted(false); it.acquire() } } catch (_: Exception) { null }
        var sock: DatagramSocket? = null
        try {
            sock = DatagramSocket(null); sock.reuseAddress = true; sock.bind(InetSocketAddress(0)); net.bindSocket(sock)
            sock.soTimeout = 500
            val msg = String.format(probeXml, UUID.randomUUID().toString()).toByteArray()
            sock.send(DatagramPacket(msg, msg.size, InetAddress.getByName("239.255.255.250"), 3702))
            val end = SystemClock.uptimeMillis() + 3500
            val buf = ByteArray(8192)
            while (SystemClock.uptimeMillis() < end && !cancelled) {
                try { val pk = DatagramPacket(buf, buf.size); sock.receive(pk); pk.address.hostAddress?.let { found.add(it) } } catch (_: SocketTimeoutException) {}
            }
        } catch (_: Exception) {
        } finally { try { sock?.close() } catch (_: Exception) {}; try { lock?.release() } catch (_: Exception) {} }
    }
}
