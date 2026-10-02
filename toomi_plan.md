# Spesifikasi Teknis & Arsitektur Aplikasi
## **LDR Companion Overlay 3D (Android Edition)**

Aplikasi mobile Android interaktif yang menghadirkan karakter 3D melayang (*floating overlay*) di atas layar homescreen dan aplikasi lain. Aplikasi ini dirancang khusus untuk pasangan LDR Gen Z agar dapat saling berinteraksi secara *real-time* langsung melalui layar HP.

---

## 1. Ringkasan Fitur Utama

* **Floating 3D Companion Overlay:** Karakter 3D animasi melayang transparan di atas layar homescreen maupun aplikasi Android lainnya.
* **Real-time Cross-Screen Interactions:** Aksi di HP Pengguna A (misal: *poke*, kirim kado, ubah *mood*) langsung memicu animasi dan reaktifitas karakter di HP Pengguna B secara *real-time*.
* **Interactive Bubble Chat & Emotes:** Pesan singkat dan emoji bertema yang muncul sebagai balon kata di atas kepala karakter, berada di luar batas aplikasi utama.
* **Contextual & Battery-Aware Behavior:** Karakter merespons kondisi HP pasangan (misal: jika baterai pasangan di bawah 15%, karakter akan tampak lemas/tidur di layar).
* **Drag-and-Drop Floating Window:** Karakter dapat digeser ke sudut mana pun di layar HP agar tidak mengganggu aktivitas mengetik atau membaca.

---

## 2. Arsitektur Teknis Sistem Android

Aplikasi memanfaatkan komponen inti sistem operasi Android untuk menjaga karakter tetap aktif tanpa terhenti oleh sistem *background management*.

```
┌─────────────────────────────────────────────────────────┐
│                    Aplikasi Utama Android               │
│         (Auth, Customization Avatar, Settings)          │
└────────────────────────────┬────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────┐
│               Android Foreground Service                │
│         (Jaga koneksi WebSocket & State Sync)           │
└──────────────┬───────────────────────────┬──────────────┘
               │                           │
               ▼                           ▼
┌──────────────────────────────┐ ┌────────────────────────┐
│  Window Manager (Overlay)    │ │   WebSocket Manager    │
│ (SYSTEM_ALERT_WINDOW Permission)│ │   (Socket.io / Pusher) │
└──────────────┬───────────────┘ └───────────┬────────────┘
               │                            │
               ▼                            ▼
┌──────────────────────────────┐ ┌────────────────────────┐
│ Translucent Android WebView  │ │ Event Handler Sync     │
│ (Three.js / Filament 3D Engine)│ │ (Animasi & Bubble Chat)│
└──────────────────────────────┘ └────────────────────────┘
```

### Komponen Kunci OS Android:

1. **`SYSTEM_ALERT_WINDOW` (Draw over other apps):**
   * Mengizinkan tampilan jendela aplikasi berada di tingkat paling atas (*Z-order highest level*) di atas *launcher* (homescreen) dan aplikasi lain.
2. **Foreground Service dengan Notification:**
   * Memastikan *process* aplikasi tidak dihentikan oleh OS Android (*Low Memory Killer*) saat HP memasuki mode idle/doze mode.
3. **Transparent WebView / Native Canvas:**
   * Container transparan tempat menjalankan *lightweight 3D engine* (Three.js / Filament) untuk merender karakter 3D berbentuk `.gltf` / `.glb` beranimasi.

---

## 3. Alur Komunikasi Real-time (WebSocket Payload)

Komunikasi antar dua perangkat LDR diproses menggunakan event *WebSocket* terenkripsi dengan latensi rendah (< 150ms).

### Schema JSON Payload Contoh:

#### A. Event *Poke* & Ekspresi Karakter
```json
{
  "event_type": "TRIGGER_ANIMATION",
  "sender_id": "usr_alpha_123",
  "receiver_id": "usr_bravo_456",
  "timestamp": 1772546451,
  "payload": {
    "animation_code": "POKE_REACTION_HAPPY",
    "sound_effect": "giggle_01.wav",
    "bubble_chat": {
      "text": "Pasanganmu memanggilmu! 💖",
      "duration_ms": 3500
    },
    "haptic_feedback": true
  }
}
```

#### B. Event Update Status Kondisi Perangkat
```json
{
  "event_type": "UPDATE_DEVICE_STATE",
  "sender_id": "usr_alpha_123",
  "receiver_id": "usr_bravo_456",
  "timestamp": 1772546510,
  "payload": {
    "battery_level": 12,
    "is_charging": false,
    "idle_status": "SLEEPING_MODE",
    "character_state": "TIRED_IDLE"
  }
}
```

---

## 4. Desain Antarmuka & Interaksi (UI/UX Overlay)

### Gesture & Kontrol Karakter di Screensaver/Homescreen:

* **Tap Tunggal (Single Tap):** Membuka *radial menu* mini berisi aksi cepat (*Kirim Hug*, *Kirim Pok*, *Kirim Pesan Cepat*).
* **Double Tap:** Memicu animasi *affection* (karakter memberikan *love* atau lambaian tangan).
* **Long Press + Drag:** Memindahkan posisi floating avatar ke tepi layar kiri, kanan, atas, atau bawah.
* **Swipe Away:** Menyembunyikan karakter ke mode *edge-dock* (hanya terlihat ikon kecil di tepi layar) agar tidak menutupi tombol penting saat bermain game full screen.

---

## 5. Manajemen Performa & Efisiensi Baterai

Salah satu tantangan terbesar dari *floating overlay 3D* adalah penggunaan daya baterai dan RAM. Berikut adalah strategi optimasinya:

| Area Optimasi | Strategi Implementasi | Target / Limits |
| :--- | :--- | :--- |
| **Model 3D Polygon** | Gunakan model Low-Poly dengan optimasi *texture atlas*. | Max 15.000 Polygon / File < 3MB |
| **Frame Rate Control** | Batasi rendering rate saat idle dari 60 FPS ke **15-20 FPS**. | Menghemat hingga 40% CPU/GPU usage |
| **Smart Sleep State** | Saat layar HP mati (*screen-off*), pause rendering engine total. | 0% GPU Consumption saat layar mati |
| **Background Network** | Batasi ping heart-beat WebSocket saat kondisi idle. | Paket data efisien & konsumsi baterai rendah |

---

## 6. Roadmap Pengembangan Proyek

```
Phase 1: Minimal Viable Product (MVP)
  ├── Setup Android Overlay Service & Overlay Window Basic
  ├── Integrasi WebView 3D Transparan dengan Three.js
  └── Implementasi WebSocket Sederhana (Send/Receive Event)

Phase 2: Core LDR Features
  ├── Penambahan Animasi Character (Idle, Poke, Happy, Sad, Sleep)
  ├── Bubble Chat System di Overlay Screen
  └── System Event Observer (Sensors, Battery Level Sync)

Phase 3: Polish & Customization
  ├── Kustomisasi Avatar 3D (Outfit, Aksesori, Warna)
  ├── Radial Menu Popup di Overlay Window
  └── Mode Edge-Docking & Anti-Disturb Mode (Game Mode Protection)
```
```

---

### Langkah Selanjutnya
Jika Anda ingin mulai membangun *prototype* awal, kita bisa memulainya dengan membuat **file layout XML Android Overlay**, **Kotlin Foreground Service**, atau **script Three.js 3D dasar**. Mana yang ingin dieksekusi terlebih dahulu?