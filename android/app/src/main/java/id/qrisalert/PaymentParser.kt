package id.qrisalert

import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale

data class Payment(
    val id: String,
    val amount: Long,
    val source: String,
    val text: String,
    val time: Long,
    val test: Boolean = false,
) {
    fun toJson(): String = JSONObject()
        .put("id", id)
        .put("amount", amount)
        .put("source", source)
        .put("text", text)
        .put("time", time)
        .put("test", test)
        .toString()
}

object PaymentParser {

    // Menangkap "Rp50.000", "Rp 50.000", "Rp50.000,00", "IDR 50000", dan sejenisnya
    private val amountPattern = Regex("""(?:Rp|IDR)\.?\s?(\d[\d.,]*)""", RegexOption.IGNORE_CASE)
    private val decimalTail = Regex("""[.,]\d{2}$""")

    /** Nominal pertama yang ditemukan di teks, atau null bila tidak ada. */
    fun parseAmount(text: String): Long? {
        val raw = amountPattern.find(text)?.groupValues?.get(1) ?: return null
        val cleaned = raw.trimEnd('.', ',').replace(decimalTail, "")
        return cleaned.filter { it.isDigit() }.toLongOrNull()?.takeIf { it > 0 }
    }

    fun sourceName(packageName: String): String = when {
        packageName == "id.dana" -> "DANA"
        "gopay" in packageName || "gojek" in packageName -> "GoPay"
        else -> packageName.substringAfterLast('.')
    }

    fun rupiah(amount: Long): String =
        "Rp" + NumberFormat.getInstance(Locale.forLanguageTag("id-ID")).format(amount)
}
