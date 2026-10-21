# Melolo & Drama AdBlocker (Android VPN DNS Filter)

Aplikasi Android berbasis **Local VPN Service** yang berfungsi untuk memblokir iklan secara otomatis pada aplikasi pembaca novel / drama pendek seperti **Melolo**, **Fizzo**, dan aplikasi drama lainnya tanpa memerlukan akses root.

---

## 🚀 Fitur Unggulan

- **Mesin Anti-Iklan Berlapis (*Multi-Tier Ad Filter*):**
  - Pemblokiran langsung di tingkat lokal (0 ms) untuk jaringan iklan populer: Pangle SDK (ByteDance), Google AdMob, DoubleClick, AppLovin, Unity Ads, Mintegral, Rayjump, Vungle, InMobi, Moloco, IronSource, dsb.
  - Whitelist cerdas untuk memastikan API inti aplikasi, teks bab novel, cover buku, dan pemutar video drama tetap berjalan lancar tanpa terganggu.
- **Lapisan Perlindungan Kedua (*Two-Tier Shield* Upstream):**
  - Terintegrasi secara default dengan **AdGuard DNS** (`94.140.14.14` & `94.140.15.15`) untuk menangkal domain iklan varian baru dari server cloud.
  - Tersedia opsi Upstream DNS lain: **Cloudflare Security (1.1.1.2)** dan **Google DNS (8.8.8.8)**.
- **Kepatuhan Protokol DNS RFC & Caching:**
  - Mendukung kueri IPv4 (tipe A $\rightarrow$ `0.0.0.0`), IPv6 (tipe AAAA $\rightarrow$ `::`), dan HTTPS/SVCB (`NODATA`).
  - Cache TTL 300 detik untuk mencegah SDK iklan melakukan *spam retry*.
- **Pilihan Cakupan Perlindungan (*Scope Mode*):**
  - **Mode Global (Semua Aplikasi - Rekomendasi):** Melindungi seluruh sistem ponsel termasuk iklan mediasi di proses Google Play Services dan WebView.
  - **Mode Khusus Melolo:** Menyaring paket khusus aplikasi Melolo dan layanan pendukungnya.
- **Log Interaktif & Analisis AI:**
  - Pantau kueri DNS yang diblokir atau diizinkan secara *real-time*.
  - Tombol aksi cepat untuk menambah atau menghapus domain ke Whitelist / Blacklist kustom.
- **Tips Anti-Bocor Terintegrasi:**
  - Panduan cepat dan tombol akses instan ke pengaturan **DNS Pribadi (Private DNS)** Android agar tidak mem-bypass VPN.

---

## 📥 Unduh & Instalasi

Anda dapat langsung mengunduh file APK yang sudah jadi:
- [**MeloloAdBlocker.apk**](./MeloloAdBlocker.apk) (Langsung pasang di perangkat Android)

Atau lakukan *build* sendiri dari sumber kode:
```bash
./gradlew assembleDebug
```
File APK akan dihasilkan di `app/build/outputs/apk/debug/MeloloAdBlocker.apk`.

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
