package id.qrisalert

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Catatan kejadian sederhana yang ditampilkan di layar utama aplikasi. */
object EventLog {

    private const val FILE_NAME = "events.log"
    private const val MAX_LINES = 150
    private val timeFormat = SimpleDateFormat("dd/MM HH:mm:ss", Locale.US)

    @Synchronized
    fun add(context: Context, message: String) {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        val old = if (file.exists()) file.readLines() else emptyList()
        val entry = "${timeFormat.format(Date())}  ${message.replace('\n', ' ')}"
        file.writeText((listOf(entry) + old).take(MAX_LINES).joinToString("\n"))
    }

    @Synchronized
    fun read(context: Context): String {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        return if (file.exists()) file.readText() else ""
    }

    @Synchronized
    fun clear(context: Context) {
        File(context.applicationContext.filesDir, FILE_NAME).delete()
    }
}
