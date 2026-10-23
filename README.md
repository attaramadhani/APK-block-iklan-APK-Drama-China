# Melolo & Drama AdBlocker (Android VPN DNS Filter)

Aplikasi Android berbasis **Local VPN Service** yang berfungsi untuk memblokir iklan secara otomatis pada aplikasi pembaca novel / drama pendek seperti **Melolo**, **Fizzo**, dan aplikasi drama lainnya tanpa memerlukan akses root.

---

## 🆕 Yang Baru di Versi 2.0

- **🤖 Asisten AI Pemblokir** — menganalisis setiap domain dan memutuskan mana yang **aman diblokir** dan mana yang **jangan diblokir**:
  - **AI Lokal (offline, gratis, otomatis):** basis pengetahuan dari hasil bedah APK Melolo (`com.worldance.drama`) + analisis pola subdomain ByteDance (`mon-*`, `rtlog-*`, `log-*`, `tnc-*`) + model skor berbasis token (skor iklan 0–100% dan tingkat keyakinan).
  - **AI Online (opsional, Gemini):** isi API key gratis dari [aistudio.google.com/apikey](https://aistudio.google.com/apikey), lalu tekan **Analisis Online** untuk menilai domain yang belum dikenal. Hanya nama domain yang dikirim. Domain inti Melolo tidak akan pernah diblokir walaupun AI Online menyarankan.
  - Dialog **Analisis AI** menampilkan rekomendasi, kategori, skor, alasan, dan efek jika diblokir, plus tombol **Terapkan Semua Rekomendasi AI**.
- **Perbaikan besar engine filter:** versi lama memblokir *semua* domain yang tidak ada di whitelist sehingga video Melolo & aplikasi lain bisa rusak. Sekarang domain tak dikenal dinilai AI dan hanya diblokir jika skornya tinggi.
- **Daftar iklan Melolo lengkap:** Pangle (`pangle.io`, `i18n-pglstatp.com`, `tiktokpangle-*`), AppLovin, Moloco, InMobi, Vungle/Liftoff, Unity Ads, Mintegral, AdMob/IMA, Facebook Audience Network, AppsFlyer, Firebase Analytics, serta pemblokiran **HTTPDNS ByteDance** agar aplikasi tidak bisa melewati filter.
- **Mode Agresif (opsional):** ikut memblokir telemetri `log.*` ByteDance. Matikan jika video gagal diputar.
- **Dukungan DNS IPv6** (sebelumnya query IPv6 diabaikan sehingga koneksi bisa lambat), **cache DNS 60 detik**, dan VPN bisa dipulihkan otomatis oleh sistem / Always-on VPN.

> ⚠️ Batasan: pemblokiran berbasis DNS tidak bisa menghapus iklan yang dikirim dari server yang sama dengan konten (misalnya `api.tmtreader.com`). Episode yang dikunci dengan "tonton iklan untuk membuka" mungkin tidak bisa dibuka karena iklannya terblokir.

## 🚀 Fitur Unggulan

- **Mesin Anti-Iklan Berlapis:** blacklist/whitelist kustom → domain inti Melolo → basis pengetahuan iklan → AI Online → skor AI Lokal.
- **Lapisan Perlindungan Kedua (Upstream):** AdGuard DNS (default), Cloudflare Security (1.1.1.2), atau Google DNS.
- **Respon DNS sesuai RFC:** A → `0.0.0.0`, AAAA → `::`, HTTPS/SVCB → `NODATA`, TTL 300 detik agar SDK iklan tidak spam retry.
- **Cakupan:** Mode Global (semua aplikasi) atau Mode Khusus Melolo.
- **Log Interaktif:** setiap query DNS menampilkan label AI (kategori · skor) dan sumber keputusan; satu ketukan untuk blokir/izinkan.
- **Tips Anti-Bocor:** pintasan ke pengaturan **DNS Pribadi (Private DNS)** Android.

---

## 📥 Unduh & Instalasi

### Unduh APK siap pakai (disarankan)
1. Buka halaman **[Releases](https://github.com/attaramadhani/APK-block-iklan-APK-Drama-China/releases/latest)** dari HP Android.
2. Unduh file **`MeloloAdBlocker.apk`** di bagian *Assets*.
3. Buka file yang sudah diunduh, lalu tekan **Pasang**. Jika muncul peringatan, izinkan *"Instal dari sumber tidak dikenal"* untuk browser / pengelola file Anda.

> ⚠️ **Sudah pernah memasang versi lama?** Hapus dulu aplikasi *Melolo AdBlocker* yang lama sebelum memasang versi baru. APK dari Releases ditandatangani dengan kunci berbeda, sehingga Android menolak memasangnya di atas versi lama ("Aplikasi tidak terpasang").

> File `MeloloAdBlocker.apk` di root repo adalah build lama (1.0). Gunakan APK dari halaman Releases.

### Persyaratan
- Android 5.0 (Lollipop) atau lebih baru.
- Tidak perlu root.
- Tidak bisa dipakai bersamaan dengan aplikasi VPN lain (Android hanya mengizinkan satu VPN aktif).

---

## 📖 Panduan Pemakaian

### 1. Aktifkan perlindungan
1. Buka aplikasi **Melolo AdBlocker**.
2. Tekan **Aktifkan AdBlocker**.
3. Android akan menampilkan dialog *"Permintaan koneksi VPN"* → tekan **OK / Izinkan**. (VPN ini lokal; trafik Anda tidak dikirim ke server mana pun, hanya permintaan DNS yang disaring.)
4. Di Android 13+, izinkan juga **notifikasi** agar perlindungan tetap berjalan di latar belakang.
5. Status berubah menjadi **Perlindungan Aktif** (ikon hijau) dan timer mulai berjalan. Ikon kunci 🔑 muncul di status bar.

### 2. Matikan DNS Pribadi (penting!)
Tekan tombol **Buka Pengaturan DNS HP**, lalu atur **DNS Pribadi / Private DNS** ke **Mati (Off)**. Jika tidak dimatikan, iklan bisa tetap muncul karena Android melewati VPN (lihat *Tips Penting* di bawah).

### 3. Pakai Melolo seperti biasa
Buka Melolo dan tonton drama. Iklan dari Pangle, AppLovin, AdMob, dan jaringan lain akan otomatis diblokir.
- Jika Melolo sudah terbuka sebelum VPN aktif, **tutup paksa Melolo** (*Info Aplikasi → Paksa Berhenti*) lalu buka lagi agar cache DNS-nya bersih.

### 4. Pilih pengaturan perlindungan
| Pengaturan | Pilihan | Rekomendasi |
|---|---|---|
| **Cakupan Perlindungan** | *Semua Aplikasi (Global)* atau *Hanya Melolo & Layanan Terkait* | Global (iklan juga sering dimuat lewat Google Play Services & WebView) |
| **Upstream DNS** | AdGuard / Cloudflare Security / Google | AdGuard (lapisan blokir iklan kedua) |
| **AI Otomatis** | Nyala / Mati | Nyala — domain baru yang dinilai AI sebagai iklan ikut diblokir |
| **Mode Agresif** | Nyala / Mati | Mati — nyalakan hanya jika masih ada iklan; matikan lagi jika video gagal diputar |

> Perubahan *Cakupan* dan *Upstream DNS* berlaku setelah VPN dimatikan lalu dinyalakan ulang. Pengaturan AI langsung berlaku.

### 5. Gunakan Asisten AI
**AI Lokal** (offline, selalu aktif) menilai setiap domain yang dihubungi aplikasi.
1. Setelah menonton Melolo beberapa menit, gulir ke kartu **Log Aktivitas DNS**. Setiap baris menampilkan domain, status (DIBLOKIR/DIIZINKAN), label AI (kategori · skor iklan), dan sumber keputusan.
2. Tekan **Analisis AI**. Untuk setiap domain akan muncul:
   - 🟢 **AMAN DIBLOKIR** — iklan/pelacak, tidak ada fitur yang rusak.
   - 🟡 **PERLU DITINJAU** — mencurigakan; hanya diblokir di Mode Agresif.
   - 🔴 **JANGAN DIBLOKIR** — server inti (video, gambar, login, pembayaran).
   - Fungsi domain, efek jika diblokir, alasan AI, dan status saat ini.
3. Tekan **Blokir / Izinkan** pada domain tertentu, atau **Terapkan Semua Rekomendasi AI** untuk menyesuaikan semuanya sekaligus.

**AI Online (opsional, Gemini)** — untuk analisis kedua pada domain yang belum dikenal AI Lokal:
1. Buat API key gratis di **[aistudio.google.com/apikey](https://aistudio.google.com/apikey)** (login dengan akun Google → *Create API key*).
2. Tempel key di kolom **API key Gemini** pada kartu *Asisten AI Pemblokir*, lalu tekan **Simpan**.
3. Tekan **Analisis Online**. Keputusan AI Online disimpan dan langsung dipakai filter (jika *AI Otomatis* menyala).
4. Tekan **Reset AI Online** untuk menghapus semua keputusan AI Online.

> Hanya **nama domain** yang dikirim ke Gemini — bukan isi trafik atau data pribadi. Domain inti Melolo tidak akan pernah diblokir walaupun AI Online menyarankannya.

### 6. Atur daftar kustom secara manual
- **Izinkan Domain Kustom (Whitelist):** ketik domain (mis. `contoh.com`) → **Tambah**. Domain beserta semua subdomainnya selalu diizinkan.
- **Blokir Domain Kustom (Blacklist):** ketik domain → **Tambah**. Prioritas tertinggi, selalu diblokir.
- Tombol **Izinkan / Blokir** pada setiap baris log juga langsung memasukkan domain ke daftar ini.

### 7. Matikan perlindungan
Tekan **Matikan AdBlocker** di aplikasi, atau tombol **Matikan AdBlocker** di notifikasi.

---

## 🔧 Mengatasi Masalah

| Masalah | Solusi |
|---|---|
| Video / drama tidak bisa diputar atau loading terus | Matikan **Mode Agresif**. Buka **Analisis AI**, cari domain berstatus DIBLOKIR yang terlihat seperti server video (mis. berisi `video`, `vod`, `cdn`) lalu tekan **Izinkan**. |
| Iklan masih muncul | Pastikan **DNS Pribadi** mati, pilih cakupan **Global**, tutup paksa Melolo lalu buka lagi. Jika masih muncul, coba nyalakan **Mode Agresif** atau gunakan **Analisis Online**. |
| Episode "tonton iklan untuk membuka" tidak bisa dibuka | Ini efek dari iklan yang terblokir. Matikan AdBlocker sementara untuk episode tersebut. |
| Aplikasi lain tidak bisa internetan | Buka **Analisis AI** dan izinkan domain aplikasi tersebut, atau pilih cakupan **Hanya Melolo**. |
| "Aplikasi tidak terpasang" saat install | Hapus versi lama Melolo AdBlocker terlebih dahulu. |
| AI Online gagal | Periksa API key; error *HTTP 429* berarti kuota gratis Gemini sedang habis — coba lagi nanti. |
| VPN mati sendiri | Matikan optimasi baterai untuk Melolo AdBlocker (*Pengaturan → Baterai → Tanpa batasan*). |

> ⚠️ Batasan: pemblokiran DNS tidak bisa menghapus iklan yang dikirim dari server yang sama dengan konten drama.

---

## 🛠️ Build dari Sumber Kode

Setiap push ke branch `main` otomatis di-build oleh **GitHub Actions** dan diterbitkan di halaman Releases.

Build manual (Android Studio atau terminal, butuh JDK 17 + Android SDK 34):
```bash
./gradlew assembleDebug
```
APK dihasilkan di `app/build/outputs/apk/debug/MeloloAdBlocker.apk`.

Uji unit:
```bash
./gradlew testDebugUnitTest
```

---

## 💡 Tips Penting: Pencegahan Iklan Bocor
Pada beberapa perangkat Android (Xiaomi MIUI/HyperOS, Samsung OneUI, Oppo ColorOS, Vivo FuntouchOS):
1. Buka menu **Pengaturan HP** > **Koneksi / Jaringan**.
2. Cari menu **DNS Pribadi (Private DNS)**.
3. Atur statusnya menjadi **Mati (Off)**.
> *Jika DNS Pribadi aktif (Otomatis/Hostname), sistem Android akan mem-bypass interface VPN dan langsung menghubungi DNS TLS penyedia ponsel.*

---

## 🛠️ Lisensi & Kontribusi
Proyek ini dibuat untuk tujuan edukasi dan peningkatan kenyamanan membaca novel/drama bebas iklan pengganggu.
