-- ==========================================
-- TOOMI: Multi-Friend Pairing, Google/Email OTP Auth, & Single Device Session Management
-- Supabase PostgreSQL Migration (100% Idempotent & Safe)
-- ==========================================

-- 1. Enable UUID Extension & Cryptography
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- 2. Profiles Table - Update kolom jika sudah ada
CREATE TABLE IF NOT EXISTS public.profiles (
    id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    display_name VARCHAR(100) NOT NULL DEFAULT 'Toomi User',
    avatar_url TEXT,
    battery_level INT DEFAULT 100 CHECK (battery_level >= 0 AND battery_level <= 100),
    is_charging BOOLEAN DEFAULT FALSE,
    current_status VARCHAR(50) DEFAULT 'ACTIVE',
    character_model_id VARCHAR(50) DEFAULT 'animal-cat.glb',
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS toomi_id VARCHAR(12);
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS email VARCHAR(255);
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS active_device_id TEXT;
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS active_device_name TEXT;
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS last_device_active_at TIMESTAMPTZ;

-- 3. Function to generate human-readable unique Toomi ID (e.g., 'TM-738291' or 'TM-K9X2A8')
CREATE OR REPLACE FUNCTION generate_unique_toomi_id()
RETURNS TEXT AS $$
DECLARE
    chars TEXT := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
    candidate TEXT;
    exists_check BOOLEAN;
    i INT;
BEGIN
    LOOP
        candidate := 'TM-';
        FOR i IN 1..6 LOOP
            candidate := candidate || substr(chars, floor(random() * length(chars) + 1)::INT, 1);
        END LOOP;
        
        SELECT EXISTS(SELECT 1 FROM public.profiles WHERE toomi_id = candidate) INTO exists_check;
        IF NOT exists_check THEN
            RETURN candidate;
        END IF;
    END LOOP;
END;
$$ LANGUAGE plpgsql;

-- Isi toomi_id untuk profile yang sudah ada namun belum memiliki toomi_id
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN SELECT id FROM public.profiles WHERE toomi_id IS NULL OR toomi_id = '' LOOP
        UPDATE public.profiles 
        SET toomi_id = generate_unique_toomi_id() 
        WHERE id = r.id;
    END LOOP;
END $$;

ALTER TABLE public.profiles ALTER COLUMN toomi_id SET NOT NULL;
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'profiles_toomi_id_key'
    ) THEN
        ALTER TABLE public.profiles ADD CONSTRAINT profiles_toomi_id_key UNIQUE (toomi_id);
    END IF;
END $$;

-- 4. Trigger to automatically create profile on Auth sign up (Google or Email OTP)
CREATE OR REPLACE FUNCTION public.handle_new_user()
RETURNS TRIGGER AS $$
DECLARE
    gen_toomi_id TEXT;
    user_name TEXT;
    user_avatar TEXT;
BEGIN
    gen_toomi_id := generate_unique_toomi_id();
    user_name := COALESCE(
        NEW.raw_user_meta_data->>'full_name',
        NEW.raw_user_meta_data->>'name',
        split_part(NEW.email, '@', 1),
        'Toomi Friend'
    );
    user_avatar := NEW.raw_user_meta_data->>'avatar_url';

    INSERT INTO public.profiles (
        id,
        toomi_id,
        email,
        display_name,
        avatar_url,
        character_model_id
    ) VALUES (
        NEW.id,
        gen_toomi_id,
        NEW.email,
        user_name,
        user_avatar,
        'animal-cat.glb'
    )
    ON CONFLICT (id) DO UPDATE
    SET email = EXCLUDED.email,
        updated_at = NOW();

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

DROP TRIGGER IF EXISTS on_auth_user_created ON auth.users;
CREATE TRIGGER on_auth_user_created
    AFTER INSERT ON auth.users
    FOR EACH ROW EXECUTE FUNCTION public.handle_new_user();

-- 5. Friendships Table (1-to-Many / Multi-Friend Pairing via Toomi ID)
CREATE TABLE IF NOT EXISTS public.friendships (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    friend_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    status VARCHAR(20) DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'BLOCKED')),
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    CONSTRAINT unique_friend_pair UNIQUE (user_id, friend_id),
    CONSTRAINT not_self_friend CHECK (user_id <> friend_id)
);

-- 6. Login Requests Table (Device Kick-out / Login Approval Mechanism)
CREATE TABLE IF NOT EXISTS public.login_requests (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    requester_device_id TEXT NOT NULL,
    requester_device_name TEXT NOT NULL,
    status VARCHAR(20) DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED')),
    created_at TIMESTAMPTZ DEFAULT NOW(),
    expires_at TIMESTAMPTZ DEFAULT (NOW() + INTERVAL '2 minutes')
);

-- 7. Interactions Log (Multi-friend Companion Interactions)
CREATE TABLE IF NOT EXISTS public.interactions_log (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    sender_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    interaction_type VARCHAR(50) NOT NULL,
    payload JSONB DEFAULT '{}'::JSONB,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Pastikan kolom receiver_id ditambahkan ke interactions_log
ALTER TABLE public.interactions_log ADD COLUMN IF NOT EXISTS receiver_id UUID REFERENCES public.profiles(id) ON DELETE CASCADE;

-- 8. Helper Functions for Device Session Management
CREATE OR REPLACE FUNCTION public.request_device_session(
    p_device_id TEXT,
    p_device_name TEXT
)
RETURNS JSONB AS $$
DECLARE
    v_user_id UUID := auth.uid();
    v_current_device_id TEXT;
    v_current_device_name TEXT;
    v_req_id UUID;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    SELECT active_device_id, active_device_name 
    INTO v_current_device_id, v_current_device_name
    FROM public.profiles 
    WHERE id = v_user_id;

    IF v_current_device_id IS NULL OR v_current_device_id = p_device_id THEN
        UPDATE public.profiles
        SET active_device_id = p_device_id,
            active_device_name = p_device_name,
            last_device_active_at = NOW()
        WHERE id = v_user_id;

        RETURN jsonb_build_object(
            'status', 'GRANTED',
            'message', 'Device registered as active'
        );
    END IF;

    INSERT INTO public.login_requests (
        user_id,
        requester_device_id,
        requester_device_name,
        status
    ) VALUES (
        v_user_id,
        p_device_id,
        p_device_name,
        'PENDING'
    ) RETURNING id INTO v_req_id;

    RETURN jsonb_build_object(
        'status', 'NEED_APPROVAL',
        'request_id', v_req_id,
        'active_device_name', v_current_device_name,
        'message', 'Waiting for approval from active device'
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

CREATE OR REPLACE FUNCTION public.approve_login_request(
    p_request_id UUID
)
RETURNS JSONB AS $$
DECLARE
    v_user_id UUID := auth.uid();
    v_req RECORD;
BEGIN
    SELECT * INTO v_req FROM public.login_requests WHERE id = p_request_id AND user_id = v_user_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Login request not found';
    END IF;

    UPDATE public.login_requests
    SET status = 'APPROVED'
    WHERE id = p_request_id;

    UPDATE public.profiles
    SET active_device_id = v_req.requester_device_id,
        active_device_name = v_req.requester_device_name,
        last_device_active_at = NOW()
    WHERE id = v_user_id;

    RETURN jsonb_build_object(
        'status', 'SUCCESS',
        'message', 'Login request approved. Active session transferred.'
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

CREATE OR REPLACE FUNCTION public.reject_login_request(
    p_request_id UUID
)
RETURNS JSONB AS $$
DECLARE
    v_user_id UUID := auth.uid();
BEGIN
    UPDATE public.login_requests
    SET status = 'REJECTED'
    WHERE id = p_request_id AND user_id = v_user_id;

    RETURN jsonb_build_object(
        'status', 'REJECTED',
        'message', 'Login request rejected.'
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

CREATE OR REPLACE FUNCTION public.logout_device(
    p_device_id TEXT
)
RETURNS JSONB AS $$
DECLARE
    v_user_id UUID := auth.uid();
BEGIN
    UPDATE public.profiles
    SET active_device_id = NULL,
        active_device_name = NULL,
        last_device_active_at = NOW()
    WHERE id = v_user_id AND active_device_id = p_device_id;

    RETURN jsonb_build_object('status', 'LOGGED_OUT');
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

CREATE OR REPLACE FUNCTION public.add_friend_by_toomi_id(
    p_toomi_id TEXT
)
RETURNS JSONB AS $$
DECLARE
    v_user_id UUID := auth.uid();
    v_target_profile RECORD;
    v_existing RECORD;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    SELECT * INTO v_target_profile FROM public.profiles WHERE UPPER(toomi_id) = UPPER(TRIM(p_toomi_id));

    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'message', 'ID Toomi tidak ditemukan');
    END IF;

    IF v_target_profile.id = v_user_id THEN
        RETURN jsonb_build_object('success', false, 'message', 'Tidak bisa menambahkan diri sendiri');
    END IF;

    SELECT * INTO v_existing FROM public.friendships 
    WHERE (user_id = v_user_id AND friend_id = v_target_profile.id)
       OR (user_id = v_target_profile.id AND friend_id = v_user_id);

    IF FOUND THEN
        IF v_existing.status = 'ACCEPTED' THEN
            RETURN jsonb_build_object('success', false, 'message', 'Sudah berteman');
        ELSIF v_existing.status = 'PENDING' THEN
            RETURN jsonb_build_object('success', false, 'message', 'Permintaan pertemanan sudah dikirim sebelumnya');
        END IF;
    END IF;

    INSERT INTO public.friendships (user_id, friend_id, status)
    VALUES (v_user_id, v_target_profile.id, 'PENDING');

    RETURN jsonb_build_object(
        'success', true,
        'message', 'Permintaan pertemanan berhasil dikirim ke ' || v_target_profile.display_name,
        'friend_name', v_target_profile.display_name,
        'friend_toomi_id', v_target_profile.toomi_id
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- 10. Indexes
CREATE INDEX IF NOT EXISTS idx_profiles_toomi_id ON public.profiles(toomi_id);
CREATE INDEX IF NOT EXISTS idx_profiles_active_device ON public.profiles(active_device_id);
CREATE INDEX IF NOT EXISTS idx_friendships_users ON public.friendships(user_id, friend_id);
CREATE INDEX IF NOT EXISTS idx_login_requests_user ON public.login_requests(user_id, status);

-- 11. Row Level Security (RLS)
ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.friendships ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.login_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.interactions_log ENABLE ROW LEVEL SECURITY;

-- Profiles Policies
DROP POLICY IF EXISTS "Public profiles are viewable by authenticated users" ON public.profiles;
CREATE POLICY "Public profiles are viewable by authenticated users" 
ON public.profiles FOR SELECT TO authenticated USING (true);

DROP POLICY IF EXISTS "Users can update their own profile" ON public.profiles;
CREATE POLICY "Users can update their own profile" 
ON public.profiles FOR UPDATE TO authenticated USING (auth.uid() = id);

-- Friendships Policies
DROP POLICY IF EXISTS "Users can view their friendships" ON public.friendships;
CREATE POLICY "Users can view their friendships"
ON public.friendships FOR SELECT TO authenticated
USING (auth.uid() = user_id OR auth.uid() = friend_id);

DROP POLICY IF EXISTS "Users can insert friendship request" ON public.friendships;
CREATE POLICY "Users can insert friendship request"
ON public.friendships FOR INSERT TO authenticated
WITH CHECK (auth.uid() = user_id);

DROP POLICY IF EXISTS "Users can update their received or sent friendship" ON public.friendships;
CREATE POLICY "Users can update their received or sent friendship"
ON public.friendships FOR UPDATE TO authenticated
USING (auth.uid() = user_id OR auth.uid() = friend_id);

DROP POLICY IF EXISTS "Users can delete friendship" ON public.friendships;
CREATE POLICY "Users can delete friendship"
ON public.friendships FOR DELETE TO authenticated
USING (auth.uid() = user_id OR auth.uid() = friend_id);

-- Login Requests Policies
DROP POLICY IF EXISTS "Users can view and manage their login requests" ON public.login_requests;
CREATE POLICY "Users can view and manage their login requests"
ON public.login_requests FOR ALL TO authenticated
USING (auth.uid() = user_id);

-- Interactions Log Policies
DROP POLICY IF EXISTS "Users can view interactions directed to them or sent by them" ON public.interactions_log;
CREATE POLICY "Users can view interactions directed to them or sent by them"
ON public.interactions_log FOR SELECT TO authenticated
USING (auth.uid() = sender_id OR auth.uid() = receiver_id);

DROP POLICY IF EXISTS "Users can insert interactions" ON public.interactions_log;
CREATE POLICY "Users can insert interactions"
ON public.interactions_log FOR INSERT TO authenticated
WITH CHECK (auth.uid() = sender_id);

-- 12. Safe Realtime Publications (Hanya ditambahkan jika belum terdaftar)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_publication_tables 
        WHERE pubname = 'supabase_realtime' AND tablename = 'profiles'
    ) THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.profiles;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_publication_tables 
        WHERE pubname = 'supabase_realtime' AND tablename = 'friendships'
    ) THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.friendships;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_publication_tables 
        WHERE pubname = 'supabase_realtime' AND tablename = 'login_requests'
    ) THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.login_requests;
    END IF;
END $$;
