package id.qrisalert

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Mengirim data pembayaran ke server widget, dengan percobaan ulang bila gagal. */
object Sender {

    private val executor = Executors.newSingleThreadExecutor()

    fun send(context: Context, payment: Payment, onDone: ((Boolean, String) -> Unit)? = null) {
        val appContext = context.applicationContext
        val prefs = Prefs(appContext)
        val base = prefs.serverUrl
        val token = prefs.appToken
        if (base.isBlank() || token.isBlank()) {
            val message = "Alamat server atau App token belum diisi"
            EventLog.add(appContext, message)
            onDone?.invoke(false, message)
            return
        }

        executor.execute {
            var error = ""
            for (attempt in 1..3) {
                try {
                    val conn = URL("$base/api/payment").openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.setRequestProperty("Authorization", "Bearer $token")
                    conn.outputStream.use { it.write(payment.toJson().toByteArray(Charsets.UTF_8)) }
                    val code = conn.responseCode
                    conn.disconnect()

                    when {
                        code in 200..299 -> {
                            EventLog.add(appContext, "Terkirim ke widget: ${PaymentParser.rupiah(payment.amount)}")
                            onDone?.invoke(true, "OK")
                            return@execute
                        }
                        code == 401 -> {
                            error = "App token salah (HTTP 401)"
                            break
                        }
                        else -> error = "Server membalas HTTP $code"
                    }
                } catch (e: Exception) {
                    error = e.message ?: e.javaClass.simpleName
                }
                if (attempt < 3) Thread.sleep(2000L * attempt)
            }
            EventLog.add(appContext, "Gagal kirim ${PaymentParser.rupiah(payment.amount)}: $error")
            onDone?.invoke(false, error)
        }
    }
}
