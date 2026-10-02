# Setup Supabase untuk Toomi (LDR Companion Overlay 3D)

Proyek ini menggunakan Supabase Free Tier untuk:
1. **Database & Auth:** Menyimpan akun pengguna, relasi pasangan (*pairing*), dan pengaturan status.
2. **Supabase Realtime (Broadcast):** Mengirimkan event instan (Poke, animasi, balon chat, haptic) dengan latensi < 100ms via WebSockets.
3. **Supabase Realtime (Presence):** Deteksi status online/offline pasangan secara otomatis.
4. **Supabase Storage:** Menyimpan model 3D `.glb` dan audio SFX.

---

## Langkah Setup di Supabase Dashboard:

1. Buat project baru di [supabase.com](https://supabase.com).
2. Buka menu **SQL Editor** di Dashboard Supabase Anda.
3. Salin dan jalankan seluruh isi file [`migrations/20261002_init_toomi_schema.sql`](file:///home/jabrix/projects/shared-docker/www/toomi/supabase/migrations/20261002_init_toomi_schema.sql).
4. Buka menu **Database > Replication**, pastikan `supabase_realtime` aktif untuk tabel `profiles` dan `couples`.
5. Buka menu **Project Settings > API**, salin:
   - **Project URL** (misal: `https://xyzcompany.supabase.co`)
   - **anon / public key**
6. Masukkan kredensial tersebut ke konfigurasi Android (`SupabaseConfig.kt`).
