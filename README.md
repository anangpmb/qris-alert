# QRIS Alert

Widget stream untuk pembayaran QRIS, mirip cara kerja Pulsoid. HP yang menerima notifikasi DANA atau GoPay Merchant menjadi pemicu. Server meneruskan datanya secara realtime. Widget di OBS mencetak struk "LUNAS" setiap ada pembayaran baru.

Tidak ada SDK, layanan berbayar, atau dependensi tambahan. Server cukup Node.js, aplikasinya hanya memakai API bawaan Android.

```
 HP Android                      Server (PC / VPS)                 OBS
┌─────────────────────┐  POST   ┌──────────────────────┐   SSE   ┌──────────────────┐
│ DANA / GoPay        │ ──────▶ │ /api/payment         │ ──────▶ │ Browser source   │
│ notifikasi masuk    │  token  │ simpan + siarkan     │   key   │ /widget?key=...  │
│ QRIS Alert membaca  │         │ dashboard di  /      │         │ struk + bunyi    │
└─────────────────────┘         └──────────────────────┘         └──────────────────┘
```

## Isi folder

```
Mulai QRIS Alert.bat          Windows: klik dua kali untuk menyalakan
Berhenti QRIS Alert.bat       Windows: klik dua kali untuk mematikan
Autostart Windows.bat         Windows: nyala otomatis setiap masuk Windows
Mulai QRIS Alert.command      macOS/Linux: menyalakan
Berhenti QRIS Alert.command   macOS/Linux: mematikan
server/
  server.js                   server tanpa dependensi (Node.js 18+)
  public/widget.html          widget untuk OBS
  public/index.html           dashboard: atur tampilan, salin URL, kirim tes
android/                      proyek Android Studio (Kotlin)
android/preview.html          pratinjau aplikasi HP, buka langsung di browser
```

## 1. Jalankan server

Pasang [Node.js](https://nodejs.org/id/download) versi LTS satu kali saja. Setelah itu tidak perlu mengetik perintah apa pun.

**Windows:** klik dua kali `Mulai QRIS Alert.bat`. Server menyala di jendela yang diperkecil di taskbar, lalu dashboard terbuka sendiri di browser. Biarkan jendela itu selama streaming. Untuk mematikan, klik dua kali `Berhenti QRIS Alert.bat` atau tutup jendelanya.

**macOS:** klik dua kali `Mulai QRIS Alert.command`. Kalau macOS memblokirnya, klik kanan file itu lalu pilih **Open**. Ini cukup dilakukan sekali.

**Linux:** jalankan `./"Mulai QRIS Alert.command"` dari terminal.

Mengklik Mulai saat server sudah menyala tidak akan membuat server kedua. Launcher hanya membuka dashboard lagi. Kalau Node.js belum terpasang, launcher akan membuka halaman unduhannya.

### Nyala otomatis tanpa klik

Di Windows, klik dua kali `Autostart Windows.bat` sekali. Setelah itu server menyala sendiri setiap kamu masuk ke Windows, tanpa membuka dashboard. Klik file yang sama lagi untuk mematikan autostart. Kalau folder QRIS Alert dipindah, matikan lalu aktifkan lagi.

Di macOS, buka System Settings → General → Login Items, lalu tambahkan `Mulai QRIS Alert.command`.

Urutan membuka OBS dan server tidak penting. Kalau OBS dibuka lebih dulu, widget akan menunggu dan tersambung sendiri begitu server menyala.

### Kunci dan data

Saat menyala, jendela server menampilkan:

```
  Dashboard        http://localhost:3000/#key=xxxx
  URL widget OBS   http://localhost:3000/widget?key=xxxx
  Alamat untuk HP  http://192.168.1.10:3000
  App token        yyyyyyyyyyyy
```

Ada dua kunci yang berbeda. **Widget key** hanya bisa membaca, jadi aman kalau URL widget sempat terlihat. **App token** dipakai HP untuk mengirim pembayaran, jadi jangan pernah ditampilkan di stream. Keduanya tersimpan di `server/data/config.json`. Hapus file itu lalu nyalakan ulang server untuk membuat kunci baru.

Alamat dan App token tidak perlu diketik di HP. Aplikasi bisa mencari server sendiri, lalu meminta token yang kamu setujui di dashboard. Caranya ada di bagian [Pengaturan di HP](#pengaturan-di-hp).

Riwayat pembayaran tersimpan di `server/data/payments.jsonl`.

Kalau port 3000 sudah dipakai program lain, jendela server akan memberi tahu. Tambahkan `"port": 3001` ke `server/data/config.json`, lalu nyalakan ulang.

### Versi portabel (opsional)

Supaya tidak perlu memasang Node.js, misalnya untuk dibawa di flashdisk, buat folder `runtime` di samping file Mulai. Lalu taruh `node.exe` (Windows) atau `node` (macOS/Linux) dari paket Node.js versi zip/tar di folder itu. Launcher akan memakainya lebih dulu.

### Server di VPS

Kalau server berjalan di VPS, jalankan langsung dan setel zona waktu agar "total hari ini" reset tengah malam WIB:

```bash
TZ=Asia/Jakarta node server/server.js
```

## 2. Pasang widget di OBS

1. Buka dashboard. Dashboard terbuka sendiri saat kamu menjalankan Mulai QRIS Alert.
2. Atur tampilan: struk tiap pembayaran atau total hari ini, judul, kalimat penutup, warna stempel, dan bunyi.
3. Klik **Salin URL**.
4. Di OBS: Sources → + → Browser. Tempel URL, atur lebar 400 dan tinggi 480 (mode total: 400 × 220).
5. Centang **Control audio via OBS** supaya bunyi masuk ke mixer OBS.
6. Jangan centang **Shutdown source when not visible**, supaya widget tetap terhubung.

Untuk mencoba tanpa HP, isi App token di bagian **Coba kirim pembayaran tes** pada dashboard, lalu klik **Kirim tes**. Pembayaran tes memakai stempel "TES" dan tidak dihitung ke total.

### Parameter URL widget

Dashboard sudah membuatkan URL-nya. Daftar ini untuk yang ingin mengubah manual.

| Parameter  | Contoh              | Keterangan                                        |
|------------|---------------------|---------------------------------------------------|
| `key`      | `key=abc123`        | Widget key (wajib)                                |
| `mode`     | `mode=total`        | `alert` (bawaan) atau `total`                     |
| `duration` | `duration=10`       | Lama struk tampil, 4–30 detik                     |
| `title`    | `title=Saweran`     | Judul struk                                       |
| `thanks`   | `thanks=Makasih!`   | Kalimat penutup, kosongkan untuk menyembunyikan   |
| `theme`    | `theme=dark`        | Kertas gelap                                      |
| `stamp`    | `stamp=1f7a4a`      | Warna stempel (hex tanpa #)                       |
| `sound`    | `sound=0`           | Matikan bunyi printer dan kaching                 |
| `tts`      | `tts=1`             | Bacakan nominal dengan suara browser              |
| `demo`     | `demo=1`            | Contoh pembayaran palsu terus-menerus, tanpa key  |

Suara browser (`tts=1`) tidak selalu berfungsi di OBS. HP tetap bisa membacakan nominal sendiri.

## 3. Pasang aplikasi Android

1. Pasang Android Studio, pilih **Open**, lalu buka folder `android/`.
2. Tunggu proses Gradle sync selesai. Kalau Android Studio menawarkan pembaruan versi Android Gradle Plugin, boleh diterima.
3. Sambungkan HP dengan USB debugging lalu klik **Run**, atau buat APK lewat Build → Build APK(s).

### Pengaturan di HP

1. Buka QRIS Alert, ketuk **Buka izin akses notifikasi**, lalu aktifkan QRIS Alert.
   Di Android 13 ke atas, aplikasi yang dipasang dari APK sering diblokir dengan pesan "setelan terbatas". Buka Info aplikasi QRIS Alert → menu titik tiga di pojok kanan atas → **Izinkan setelan terbatas**, lalu ulangi langkah ini.
2. Ketuk **Buka pengaturan baterai**. Matikan optimasi baterai untuk QRIS Alert **dan** aplikasi merchant (DANA / GoPay Merchant). Di HP Xiaomi, Oppo, Vivo, dan sejenisnya, aktifkan juga Autostart.
3. **Alamat server** terisi sendiri saat aplikasi pertama dibuka. HP dan PC harus berada di Wi-Fi yang sama. Kalau masih kosong, ketuk **Cari**.
4. Ketuk **Ambil** di sebelah App token. HP menampilkan kode 4 angka, dan dashboard di PC menampilkan permintaan dengan kode yang sama. Pastikan kodenya cocok, lalu klik **Izinkan** di dashboard. Token langsung terisi dan tersimpan di HP. Permintaan berlaku 2 menit.
5. Ketuk **Kirim pembayaran tes**. Struk tes harus muncul di OBS.

Kalau **Cari** tidak menemukan server, isi alamatnya manual dari baris "Alamat untuk HP" di jendela server, misalnya `http://192.168.1.10:3000`. Kalau ada beberapa alamat, biasanya yang benar berawalan `192.168.`, karena alamat lain sering milik adaptor virtual. Penyebab yang sering muncul:

- Windows Firewall memblokir Node.js. Saat server pertama kali menyala, Windows menanyakan izin. Pilih **Private networks**. Pencarian memakai port UDP `39300`.
- Router dengan mode isolasi tamu (*AP isolation*), biasanya di Wi-Fi tamu atau Wi-Fi umum. HP tidak bisa melihat PC sama sekali, jadi pakai Wi-Fi utama.

**Ambil** hanya bisa disetujui dari dashboard yang dibuka di PC yang menjalankan server (`localhost`). Ini mencegah orang lain di Wi-Fi yang sama mendapatkan token. Kalau server berjalan di VPS atau dashboard dibuka dari komputer lain, salin App token dari jendela server lalu tempel ke kolom App token di HP.

### Mencari nama paket dan kata kunci

Aplikasi hanya membaca notifikasi dari aplikasi yang namanya tercantum di **Aplikasi yang dipantau**. Bawaannya `id.dana`. Untuk GoPay Merchant atau aplikasi lain:

1. Nyalakan **Mode deteksi**.
2. Minta seseorang membayar nominal kecil ke QRIS kamu.
3. Ketuk **Muat ulang** di bagian Catatan. Akan muncul baris seperti
   `[deteksi] nama.paket.aplikasi: Pembayaran diterima | Rp5.000 ...`
4. Salin nama paketnya ke **Aplikasi yang dipantau** (pisahkan dengan koma kalau lebih dari satu).
5. Pilih kata yang selalu muncul di notifikasi pembayaran, misalnya "diterima", lalu isi di **Kata kunci**.
6. Matikan lagi Mode deteksi, karena mode ini mencatat notifikasi dari semua aplikasi.

Kata kunci penting untuk DANA. DANA Bisnis berada di dalam aplikasi DANA biasa, jadi notifikasi promo seperti "Cashback Rp10.000" datang dari aplikasi yang sama. Tanpa kata kunci, promo itu bisa ikut tampil sebagai pembayaran.

## Opsional: HP pakai data seluler

Kalau HP tidak satu Wi-Fi dengan PC, server perlu bisa diakses dari internet. Pilihan termudah tanpa biaya adalah Cloudflare Tunnel:

```bash
cloudflared tunnel --url http://localhost:3000
```

Perintah itu memberi alamat `https://....trycloudflare.com`. Isi alamat tersebut sebagai Alamat server di HP. Alamat ini berganti setiap kali perintah dijalankan, jadi untuk pemakaian rutin lebih nyaman memakai tunnel bernama atau VPS sendiri. OBS tetap boleh memakai `http://localhost:3000`.

## Memakai MacroDroid sebagai pengganti aplikasi

Server ini bisa menerima data dari apa saja yang bisa mengirim HTTP. Di macro MacroDroid yang sudah kamu buat, tambahkan aksi **HTTP Request**:

```
POST    http://ALAMAT-SERVER:3000/api/payment
Header  Authorization: Bearer APP_TOKEN
Header  Content-Type: application/json
Body    {"text": "{notification}", "source": "DANA"}
```

Server akan mengambil nominal dari teks notifikasi. Kalau kamu sudah mengekstrak nominalnya sendiri, kirim `"amount"` saja, misalnya `{"amount": "[lv=nominal]", "source": "DANA"}`. Format `50000`, `"50.000"`, dan `"Rp50.000,00"` semuanya diterima.

## Keamanan dan privasi

- Teks notifikasi asli bisa berisi nama pembayar. Teks itu disimpan di `data/payments.jsonl` di server kamu sendiri, tetapi **tidak** dikirim ke widget. Widget hanya menerima nominal, sumber, dan waktu.
- Hanya notifikasi dari aplikasi yang dipilih yang diproses. Pesan WhatsApp berisi "Pembayaran diterima Rp50.000" tidak akan memicu widget.
- Struk di widget adalah alat bantu, bukan bukti pembayaran. Notifikasi bisa telat atau hilang kalau sinyal buruk atau HP mematikan aplikasi. Untuk nominal besar, tetap cek riwayat di aplikasi merchant.

## Batasan

- Semua bergantung pada notifikasi aplikasi merchant. Kalau formatnya berubah, sesuaikan kata kunci lewat Mode deteksi.
- Nominal yang diambil adalah angka "Rp" pertama di notifikasi.
- HP harus tetap menyala, terhubung internet, dan login di aplikasi merchant.
- Total hari ini hanya menghitung pembayaran yang berhasil dikirim ke server.
