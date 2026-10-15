package id.qrisalert

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import java.security.MessageDigest
import java.util.Locale

/**
 * Membaca notifikasi dari aplikasi merchant (DANA, GoPay Merchant, dll.),
 * mengambil nominalnya, membacakannya, lalu mengirimnya ke server widget.
 */
class PaymentListenerService : NotificationListenerService(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val seen = LinkedHashMap<String, Long>()

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val result = tts?.setLanguage(Locale.forLanguageTag("id-ID"))
        ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        if (!ttsReady) EventLog.add(this, "Suara Bahasa Indonesia belum terpasang di pengaturan text-to-speech HP")
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onListenerConnected() {
        EventLog.add(this, "Pemantau notifikasi aktif")
    }

    override fun onListenerDisconnected() {
        EventLog.add(this, "Pemantau notifikasi terputus, mencoba menyambung ulang")
        NotificationListenerService.requestRebind(ComponentName(this, PaymentListenerService::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val notification = sbn.notification ?: return
        if (sbn.isOngoing || (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val text = listOf(title, body).filter { it.isNotBlank() }.joinToString(" | ")
        if (text.isBlank()) return

        val prefs = Prefs(this)
        if (prefs.detectMode) {
            EventLog.add(this, "[deteksi] ${sbn.packageName}: ${text.take(160)}")
        }

        // Hanya aplikasi yang dipilih, supaya notifikasi palsu dari aplikasi lain tidak ikut terbaca
        if (sbn.packageName !in prefs.packageList()) return

        val keywords = prefs.keywordList()
        if (keywords.isNotEmpty() && keywords.none { text.contains(it, ignoreCase = true) }) {
            if (text.contains("Rp", ignoreCase = true)) {
                EventLog.add(this, "Diabaikan, kata kunci tidak cocok: ${text.take(100)}")
            }
            return
        }

        val amount = PaymentParser.parseAmount(text)
        if (amount == null) {
            EventLog.add(this, "Cocok filter, tapi nominal tidak ditemukan: ${text.take(100)}")
            return
        }

        val postedAt = if (notification.`when` > 0) notification.`when` else sbn.postTime
        val id = hash("${sbn.key}|$text|$postedAt")
        if (!markSeen(id)) return

        val source = PaymentParser.sourceName(sbn.packageName)
        EventLog.add(this, "Pembayaran ${PaymentParser.rupiah(amount)} via $source")

        if (prefs.speakLocally && ttsReady) {
            tts?.speak("Pembayaran diterima, $amount rupiah", TextToSpeech.QUEUE_ADD, null, id)
        }

        Sender.send(this, Payment(id, amount, source, text, System.currentTimeMillis()))
    }

    /** true bila notifikasi ini belum pernah diproses dalam 10 menit terakhir. */
    @Synchronized
    private fun markSeen(id: String): Boolean {
        val now = System.currentTimeMillis()
        seen.entries.removeAll { now - it.value > 10 * 60 * 1000 }
        if (seen.containsKey(id)) return false
        seen[id] = now
        return true
    }

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(20)
}
