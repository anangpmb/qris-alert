package id.qrisalert

import android.content.Context

/** Pengaturan aplikasi, disimpan di SharedPreferences. */
class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("qris_alert", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString(KEY_SERVER, "").orEmpty()
        set(value) {
            var url = value.trim().trimEnd('/')
            if (url.isNotEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) {
                url = "http://$url"
            }
            sp.edit().putString(KEY_SERVER, url).apply()
        }

    var appToken: String
        get() = sp.getString(KEY_TOKEN, "").orEmpty()
        set(value) = sp.edit().putString(KEY_TOKEN, value.trim()).apply()

    /** Nama paket aplikasi merchant yang dipantau, dipisah koma. */
    var packages: String
        get() = sp.getString(KEY_PACKAGES, DEFAULT_PACKAGES).orEmpty()
        set(value) = sp.edit().putString(KEY_PACKAGES, value).apply()

    /** Kata yang harus ada di notifikasi pembayaran, dipisah koma. Kosong = semua notifikasi berisi "Rp". */
    var keywords: String
        get() = sp.getString(KEY_KEYWORDS, DEFAULT_KEYWORDS).orEmpty()
        set(value) = sp.edit().putString(KEY_KEYWORDS, value).apply()

    var speakLocally: Boolean
        get() = sp.getBoolean(KEY_SPEAK, true)
        set(value) = sp.edit().putBoolean(KEY_SPEAK, value).apply()

    var detectMode: Boolean
        get() = sp.getBoolean(KEY_DETECT, false)
        set(value) = sp.edit().putBoolean(KEY_DETECT, value).apply()

    fun packageList(): List<String> = split(packages)

    fun keywordList(): List<String> = split(keywords)

    private fun split(text: String): List<String> =
        text.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        private const val KEY_SERVER = "server_url"
        private const val KEY_TOKEN = "app_token"
        private const val KEY_PACKAGES = "packages"
        private const val KEY_KEYWORDS = "keywords"
        private const val KEY_SPEAK = "speak_locally"
        private const val KEY_DETECT = "detect_mode"

        const val DEFAULT_PACKAGES = "id.dana"
        const val DEFAULT_KEYWORDS = "diterima, menerima"
    }
}
