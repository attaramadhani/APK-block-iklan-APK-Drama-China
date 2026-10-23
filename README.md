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

Build dari sumber kode (Android Studio atau terminal):
```bash
./gradlew assembleDebug
```
File APK akan dihasilkan di `app/build/outputs/apk/debug/MeloloAdBlocker.apk`.

> File `MeloloAdBlocker.apk` di root repo adalah build versi lama (1.0). Build ulang untuk mendapatkan versi 2.0.

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
