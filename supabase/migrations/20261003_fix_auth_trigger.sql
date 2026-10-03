-- ==========================================
-- FIX: Supabase Auth Trigger (Database Error Saving New User Fix)
-- ==========================================

-- 1. Pastikan kolom di tabel profiles fleksibel dan aman
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS toomi_id VARCHAR(12);
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS email VARCHAR(255);
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS display_name VARCHAR(100) DEFAULT 'Toomi User';
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS avatar_url TEXT;
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS active_device_id TEXT;
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS active_device_name TEXT;
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS last_device_active_at TIMESTAMPTZ;
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS battery_level INT DEFAULT 100;
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS is_charging BOOLEAN DEFAULT FALSE;
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS current_status VARCHAR(50) DEFAULT 'ACTIVE';
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS character_model_id VARCHAR(50) DEFAULT 'animal-cat.glb';

-- 2. Fungsi pembuat Toomi ID dengan fallback aman
CREATE OR REPLACE FUNCTION public.generate_unique_toomi_id()
RETURNS TEXT 
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth, pg_temp
AS $$
DECLARE
    chars TEXT := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
    candidate TEXT;
    exists_check BOOLEAN;
    i INT;
BEGIN
    FOR attempt IN 1..10 LOOP
        candidate := 'TM-';
        FOR i IN 1..6 LOOP
            candidate := candidate || substr(chars, floor(random() * length(chars) + 1)::INT, 1);
        END LOOP;
        
        SELECT EXISTS(SELECT 1 FROM public.profiles WHERE toomi_id = candidate) INTO exists_check;
        IF NOT exists_check THEN
            RETURN candidate;
        END IF;
    END LOOP;

    -- Fallback jika loop terlewati
    RETURN 'TM-' || upper(substr(md5(random()::text), 1, 6));
END;
$$;

-- 3. Trigger Function Anti-Error (SECURITY DEFINER + EXCEPTION HANDLER)
CREATE OR REPLACE FUNCTION public.handle_new_user()
RETURNS TRIGGER 
LANGUAGE plpgsql 
SECURITY DEFINER
SET search_path = public, auth, pg_temp
AS $$
DECLARE
    gen_toomi_id TEXT;
    user_name TEXT;
    user_avatar TEXT;
BEGIN
    -- Buat toomi_id
    BEGIN
        gen_toomi_id := public.generate_unique_toomi_id();
    EXCEPTION WHEN OTHERS THEN
        gen_toomi_id := 'TM-' || upper(substr(md5(random()::text), 1, 6));
    END;

    -- Tentukan nama
    user_name := COALESCE(
        NEW.raw_user_meta_data->>'full_name',
        NEW.raw_user_meta_data->>'name',
        NEW.raw_user_meta_data->>'user_name',
        split_part(NEW.email, '@', 1),
        'Toomi User'
    );
    IF user_name IS NULL OR trim(user_name) = '' THEN
        user_name := 'Toomi User';
    END IF;

    user_avatar := NEW.raw_user_meta_data->>'avatar_url';

    -- Insert atau Update profil
    INSERT INTO public.profiles (
        id,
        toomi_id,
        email,
        display_name,
        avatar_url,
        battery_level,
        is_charging,
        current_status,
        character_model_id
    ) VALUES (
        NEW.id,
        gen_toomi_id,
        NEW.email,
        user_name,
        user_avatar,
        100,
        false,
        'ACTIVE',
        'animal-cat.glb'
    )
    ON CONFLICT (id) DO UPDATE
    SET email = COALESCE(EXCLUDED.email, public.profiles.email),
        display_name = CASE 
            WHEN public.profiles.display_name = 'Toomi User' OR public.profiles.display_name IS NULL 
            THEN EXCLUDED.display_name 
            ELSE public.profiles.display_name 
        END,
        avatar_url = COALESCE(EXCLUDED.avatar_url, public.profiles.avatar_url),
        updated_at = NOW();

    RETURN NEW;
EXCEPTION WHEN OTHERS THEN
    -- Logging error tanpa membatalkan pembuatan user di auth.users
    RAISE WARNING 'handle_new_user error: %', SQLERRM;
    RETURN NEW;
END;
$$;

-- Pasang ulang trigger
DROP TRIGGER IF EXISTS on_auth_user_created ON auth.users;
CREATE TRIGGER on_auth_user_created
    AFTER INSERT ON auth.users
    FOR EACH ROW EXECUTE FUNCTION public.handle_new_user();
