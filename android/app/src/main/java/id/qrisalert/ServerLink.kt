package id.qrisalert

import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.MalformedURLException
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Menyambungkan HP ke server tanpa mengetik:
 * - [discover] mencari server di Wi-Fi yang sama lewat broadcast UDP
 * - [requestPair] dan [checkPair] meminta App token yang harus disetujui di dashboard PC
 * Semua fungsi memblokir, panggil dari thread latar.
 */
object ServerLink {

    private const val DISCOVERY_PORT = 39300
    private const val DISCOVERY_ASK = "qris-alert?"

    data class Found(val url: String, val name: String)

    data class Ticket(val id: String, val code: String, val expiresInSec: Int)

    sealed class PairResult {
        data class Approved(val token: String) : PairResult()
        object Pending : PairResult()
        object Denied : PairResult()
        object Expired : PairResult()
    }

    /** Galat dengan pesan yang siap ditampilkan ke pengguna. */
    class LinkException(message: String) : Exception(message)

    /** Server pertama yang membalas dalam [timeoutMs], atau null. */
    fun discover(timeoutMs: Long = 2500): Found? {
        val ask = DISCOVERY_ASK.toByteArray(Charsets.UTF_8)
        val targets = (broadcastAddresses() + InetAddress.getByName("255.255.255.255")).distinct()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 400
            val buffer = ByteArray(512)
            val deadline = System.currentTimeMillis() + timeoutMs
            var nextSend = 0L
            while (System.currentTimeMillis() < deadline) {
                // Kirim ulang tiap 800 ms, paket UDP pertama kadang hilang di Wi-Fi
                if (System.currentTimeMillis() >= nextSend) {
                    for (target in targets) {
                        try {
                            socket.send(DatagramPacket(ask, ask.size, target, DISCOVERY_PORT))
                        } catch (e: IOException) {
                            // Antarmuka ini tidak bisa broadcast, coba yang lain
                        }
                    }
                    nextSend = System.currentTimeMillis() + 800
                }
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                val json = runCatching { JSONObject(String(packet.data, 0, packet.length, Charsets.UTF_8)) }
                    .getOrNull() ?: continue
                if (json.optString("app") != "qris-alert") continue
                val host = packet.address.hostAddress ?: continue
                return Found("http://$host:${json.optInt("port", 3000)}", json.optString("name"))
            }
        }
        return null
    }

    private fun broadcastAddresses(): List<InetAddress> =
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses }
                .mapNotNull { it.broadcast }
        }.getOrDefault(emptyList())

    /** Minta App token. Pengguna lalu mencocokkan [Ticket.code] dan menyetujuinya di dashboard. */
    fun requestPair(base: String, device: String): Ticket {
        val (code, body) = http("POST", "$base/api/pair", JSONObject().put("device", device).toString())
        when (code) {
            200 -> Unit
            404, 405 -> throw LinkException("Server ini belum mendukung Ambil. Perbarui server QRIS Alert, atau tempel token dari jendela server.")
            429 -> throw LinkException("Terlalu banyak permintaan. Tunggu sebentar, lalu coba lagi.")
            else -> throw LinkException("Server membalas HTTP $code.")
        }
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: throw LinkException("Balasan server tidak dikenali. Pastikan alamatnya server QRIS Alert.")
        return Ticket(json.getString("id"), json.getString("code"), json.optInt("expiresIn", 120))
    }

    fun checkPair(base: String, id: String): PairResult {
        val (code, body) = try {
            http("GET", "$base/api/pair/$id", null)
        } catch (e: IOException) {
            return PairResult.Pending // jaringan putus sebentar, tanya lagi nanti
        }
        if (code == 404) return PairResult.Expired
        if (code != 200) return PairResult.Pending
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return PairResult.Pending
        return when (json.optString("status")) {
            "approved" -> json.optString("token").takeIf { it.isNotBlank() }
                ?.let { PairResult.Approved(it) } ?: PairResult.Expired
            "denied" -> PairResult.Denied
            "pending" -> PairResult.Pending
            else -> PairResult.Expired
        }
    }

    /** Batalkan permintaan di server supaya tidak tertinggal di dashboard. */
    fun cancelPair(base: String, id: String) {
        runCatching { http("DELETE", "$base/api/pair/$id", null) }
    }

    private fun http(method: String, url: String, body: String?): Pair<Int, String> {
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: MalformedURLException) {
            throw LinkException("Alamat server tidak valid.")
        }
        try {
            conn.requestMethod = method
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            return code to (stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            conn.disconnect()
        }
    }
}
