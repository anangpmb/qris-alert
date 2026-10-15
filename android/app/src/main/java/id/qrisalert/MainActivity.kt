package id.qrisalert

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.IOException
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var accessDot: View
    private lateinit var accessStatus: TextView
    private lateinit var server: EditText
    private lateinit var serverHint: TextView
    private lateinit var btnFind: Button
    private lateinit var token: EditText
    private lateinit var tokenHint: TextView
    private lateinit var btnPair: Button
    private lateinit var pairBox: View
    private lateinit var pairCode: TextView
    private lateinit var packages: EditText
    private lateinit var keywords: EditText
    private lateinit var speak: Switch
    private lateinit var detect: Switch
    private lateinit var log: TextView

    /** Naik setiap kali Ambil dimulai, dibatalkan, atau layar ditutup, supaya polling lama berhenti. */
    @Volatile private var pairRun = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        accessDot = findViewById(R.id.accessDot)
        accessStatus = findViewById(R.id.accessStatus)
        server = findViewById(R.id.server)
        serverHint = findViewById(R.id.serverHint)
        btnFind = findViewById(R.id.btnFind)
        token = findViewById(R.id.token)
        tokenHint = findViewById(R.id.tokenHint)
        btnPair = findViewById(R.id.btnPair)
        pairBox = findViewById(R.id.pairBox)
        pairCode = findViewById(R.id.pairCode)
        packages = findViewById(R.id.packages)
        keywords = findViewById(R.id.keywords)
        speak = findViewById(R.id.speak)
        detect = findViewById(R.id.detect)
        log = findViewById(R.id.log)

        // Potong ripple baris mengikuti sudut kartu
        findViewById<View>(R.id.permCard).clipToOutline = true
        findViewById<View>(R.id.optionsCard).clipToOutline = true

        server.setText(prefs.serverUrl)
        token.setText(prefs.appToken)
        packages.setText(prefs.packages)
        keywords.setText(prefs.keywords)
        speak.isChecked = prefs.speakLocally
        detect.isChecked = prefs.detectMode

        speak.setOnCheckedChangeListener { _, checked -> prefs.speakLocally = checked }
        detect.setOnCheckedChangeListener { _, checked ->
            prefs.detectMode = checked
            if (checked) toast("Mode deteksi aktif. Lakukan pembayaran kecil, lalu lihat catatan.")
        }

        findViewById<View>(R.id.rowAccess).setOnClickListener {
            open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<View>(R.id.rowBattery).setOnClickListener {
            open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        btnFind.setOnClickListener { findServer(userAsked = true) }
        btnPair.setOnClickListener { requestToken() }
        findViewById<Button>(R.id.btnPairCancel).setOnClickListener { cancelPairing() }
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            save()
            toast("Pengaturan disimpan")
        }
        findViewById<Button>(R.id.btnTest).setOnClickListener { sendTest() }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener { refresh() }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            EventLog.clear(this)
            refresh()
        }

        // Pertama kali dibuka: cari server sendiri supaya alamatnya terisi otomatis
        if (prefs.serverUrl.isBlank()) findServer(userAsked = false)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        pairRun++
        super.onDestroy()
    }

    private fun save() {
        prefs.serverUrl = server.text.toString()
        prefs.appToken = token.text.toString()
        prefs.packages = packages.text.toString()
        prefs.keywords = keywords.text.toString()
        server.setText(prefs.serverUrl)
    }

    // ---------- Cari server ----------

    private fun findServer(userAsked: Boolean) {
        btnFind.isEnabled = false
        setHint(serverHint, "Mencari server di Wi-Fi ini…")
        thread {
            val found = runCatching { ServerLink.discover() }.getOrNull()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                btnFind.isEnabled = true
                when {
                    found != null -> {
                        prefs.serverUrl = found.url
                        server.setText(prefs.serverUrl)
                        val where = if (found.name.isBlank()) "" else " di ${found.name}"
                        setHint(serverHint, "Server ditemukan$where. Lanjut ketuk Ambil.")
                        if (prefs.appToken.isBlank() && pairBox.visibility != View.VISIBLE) {
                            setHint(tokenHint, "Ketuk Ambil, lalu klik Izinkan di dashboard PC.")
                        }
                    }
                    userAsked -> setHint(
                        serverHint,
                        "Server tidak ditemukan. Pastikan server menyala dan HP satu Wi-Fi dengan PC, atau isi alamatnya manual.",
                        error = true,
                    )
                    else -> setHint(serverHint, "Ketuk Cari untuk menemukan server di Wi-Fi yang sama.")
                }
            }
        }
    }

    // ---------- Ambil App token ----------

    private fun requestToken() {
        prefs.serverUrl = server.text.toString()
        server.setText(prefs.serverUrl)
        val base = prefs.serverUrl
        if (base.isBlank()) {
            setHint(tokenHint, "Isi alamat server dulu, atau ketuk Cari.", error = true)
            server.requestFocus()
            return
        }
        val run = ++pairRun
        btnPair.isEnabled = false
        setHint(tokenHint, "Menghubungi server…")

        thread {
            val ticket = try {
                ServerLink.requestPair(base, deviceName())
            } catch (e: ServerLink.LinkException) {
                onPair(run) { pairFailed(e.message.orEmpty()) }
                return@thread
            } catch (e: IOException) {
                onPair(run) { pairFailed("Server tidak bisa dihubungi. Periksa alamatnya dan pastikan HP satu Wi-Fi dengan PC.") }
                return@thread
            } catch (e: Exception) {
                onPair(run) { pairFailed("Balasan server tidak dikenali. Pastikan alamatnya server QRIS Alert.") }
                return@thread
            }

            onPair(run) { showPairCode(ticket) }
            val deadline = System.currentTimeMillis() + ticket.expiresInSec * 1000L
            while (pairRun == run && System.currentTimeMillis() < deadline) {
                Thread.sleep(1500)
                if (pairRun != run) {
                    ServerLink.cancelPair(base, ticket.id)
                    return@thread
                }
                when (val result = ServerLink.checkPair(base, ticket.id)) {
                    ServerLink.PairResult.Pending -> continue
                    is ServerLink.PairResult.Approved -> {
                        onPair(run) { pairApproved(base, result.token) }
                        return@thread
                    }
                    ServerLink.PairResult.Denied -> {
                        onPair(run) { pairFailed("Permintaan ditolak di dashboard.") }
                        return@thread
                    }
                    ServerLink.PairResult.Expired -> break
                }
            }
            onPair(run) { pairFailed("Waktu habis sebelum disetujui. Ketuk Ambil untuk mencoba lagi.") }
        }
    }

    /** Jalankan di UI thread, hanya bila permintaan ini masih yang terbaru. */
    private fun onPair(run: Int, block: () -> Unit) = runOnUiThread {
        if (run == pairRun && !isDestroyed) block()
    }

    private fun showPairCode(ticket: ServerLink.Ticket) {
        tokenHint.visibility = View.GONE
        pairCode.text = ticket.code
        pairCode.contentDescription = "Kode " + ticket.code.toCharArray().joinToString(" ")
        pairBox.visibility = View.VISIBLE
    }

    private fun pairApproved(base: String, value: String) {
        prefs.serverUrl = base
        prefs.appToken = value
        token.setText(prefs.appToken)
        endPairing()
        setHint(tokenHint, "Token tersimpan. HP siap mengirim pembayaran.")
        EventLog.add(this, "Terhubung ke $base, App token tersimpan")
        refresh()
        toast("Terhubung ke server")
    }

    private fun pairFailed(message: String) {
        endPairing()
        setHint(tokenHint, message, error = true)
    }

    private fun cancelPairing() {
        pairRun++
        endPairing()
        setHint(tokenHint, "Dibatalkan. Ketuk Ambil untuk mencoba lagi.")
    }

    private fun endPairing() {
        pairBox.visibility = View.GONE
        btnPair.isEnabled = true
    }

    private fun deviceName(): String {
        val maker = Build.MANUFACTURER.replaceFirstChar { it.titlecase() }
        return if (Build.MODEL.startsWith(maker, ignoreCase = true)) Build.MODEL else "$maker ${Build.MODEL}"
    }

    private fun setHint(view: TextView, text: String, error: Boolean = false) {
        view.text = text
        view.setTextColor(getColor(if (error) R.color.danger else R.color.text_muted))
        view.visibility = View.VISIBLE
    }

    // ---------- Lainnya ----------

    private fun sendTest() {
        save()
        val now = System.currentTimeMillis()
        val payment = Payment("tes-$now", 10_000, "Aplikasi HP", "Pembayaran tes dari aplikasi", now, test = true)
        toast("Mengirim tes…")
        Sender.send(this, payment) { ok, message ->
            runOnUiThread {
                toast(if (ok) "Tes terkirim. Cek widget di OBS." else "Gagal: $message")
                refresh()
            }
        }
    }

    private fun refresh() {
        val enabled = isListenerEnabled()
        accessStatus.text = if (enabled) "Aktif" else "Belum diizinkan. Ketuk untuk mengaktifkan."
        accessStatus.setTextColor(getColor(if (enabled) R.color.text_muted else R.color.danger))
        accessDot.backgroundTintList = ColorStateList.valueOf(getColor(if (enabled) R.color.ok else R.color.danger))
        log.text = EventLog.read(this).ifEmpty { "Belum ada catatan." }
    }

    private fun isListenerEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?: return false
        val me = ComponentName(this, PaymentListenerService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // Beberapa HP tidak punya halaman tersebut: buka info aplikasi saja
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
