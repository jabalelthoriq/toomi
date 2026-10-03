# Setup Supabase untuk Toomi (Multi-Friend 3D Companion Overlay)

Proyek ini menggunakan Supabase Free Tier untuk:
1. **Auth (Google & Email OTP):** Otentikasi aman tanpa password menggunakan OTP 6-digit ke email atau Google Sign-In.
2. **Database & Profil:** Setiap pengguna mendapatkan **ID Toomi** unik (contoh: `TM-849201`) yang digunakan untuk pairing pertemanan 1-ke-banyak (Multi-Friend).
3. **Manajemen Sesi Perangkat Tunggal (1 User = 1 HP Aktif):** Mekanisme *device session control* & *kick-out approval* ketika akun diakses dari perangkat lain.
4. **Supabase Realtime (Broadcast & Channels):**
   - Channel `user_device_control:<userId>`: Notifikasi persetujuan (ACC) login perangkat baru dan sinyal kick-out sesi lama.
   - Channel `friend_room:<userA>_<userB>`: Sinkronisasi aksi instan (Poke, Wave, balon chat, status baterai) < 100ms.
5. **Supabase Storage:** Menyimpan model 3D `.glb` dan audio SFX.

---

## Langkah Setup di Supabase Dashboard:

1. Buat project baru di [supabase.com](https://supabase.com).
2. Buka menu **SQL Editor** di Dashboard Supabase Anda.
3. Jalankan file migrasi terbaru:
   - [`migrations/20261003_toomi_multi_friend_auth_devices.sql`](file:///home/jabrix/projects/shared-docker/www/toomi/supabase/migrations/20261003_toomi_multi_friend_auth_devices.sql)
4. Buka menu **Authentication > Providers**:
   - Pastikan **Email** aktif dengan opsi **Email OTP / Magic Link**.
   - Aktifkan **Google Provider** jika ingin menggunakan Google Sign-In.
5. Buka menu **Database > Replication**, pastikan `supabase_realtime` aktif untuk tabel `profiles`, `friendships`, dan `login_requests`.
6. Buka menu **Project Settings > API**, salin:
   - **Project URL** (misal: `https://xyzcompany.supabase.co`)
   - **anon / public key**
7. Masukkan kredensial tersebut ke konfigurasi Android ([`SupabaseConfig.kt`](file:///home/jabrix/projects/shared-docker/www/toomi/android/app/src/main/java/com/toomi/app/core/config/SupabaseConfig.kt)).
