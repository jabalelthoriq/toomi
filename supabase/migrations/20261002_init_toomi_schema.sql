-- ==========================================
-- TOOMI: LDR Companion Overlay 3D Database Schema
-- Supabase PostgreSQL Migration
-- ==========================================

-- 1. Enable UUID Extension
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 2. Profiles Table (User details & real-time device state)
CREATE TABLE IF NOT EXISTS public.profiles (
    id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    username VARCHAR(50) UNIQUE,
    display_name VARCHAR(100) NOT NULL,
    avatar_url TEXT,
    battery_level INT DEFAULT 100 CHECK (battery_level >= 0 AND battery_level <= 100),
    is_charging BOOLEAN DEFAULT FALSE,
    current_status VARCHAR(50) DEFAULT 'ACTIVE', -- ACTIVE, SLEEPING, BUSY, IDLE
    character_model_id VARCHAR(50) DEFAULT 'default_mascot',
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- 3. Couples Table (Pairing between two users)
CREATE TABLE IF NOT EXISTS public.couples (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user1_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    user2_id UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    pair_code VARCHAR(8) UNIQUE NOT NULL,
    status VARCHAR(20) DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'CONNECTED', 'DISCONNECTED')),
    created_at TIMESTAMPTZ DEFAULT NOW(),
    connected_at TIMESTAMPTZ,
    CONSTRAINT unique_user_pair UNIQUE (user1_id, user2_id)
);

-- 4. Interactions Log (Optional: History of pokes, messages, gifts)
CREATE TABLE IF NOT EXISTS public.interactions_log (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    couple_id UUID NOT NULL REFERENCES public.couples(id) ON DELETE CASCADE,
    sender_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    interaction_type VARCHAR(50) NOT NULL, -- POKE, HUG, CHAT, BATTERY_ALERT, MOOD_CHANGE
    payload JSONB DEFAULT '{}'::JSONB,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- 5. Indexes for fast lookups
CREATE INDEX IF NOT EXISTS idx_couples_users ON public.couples(user1_id, user2_id);
CREATE INDEX IF NOT EXISTS idx_couples_pair_code ON public.couples(pair_code);
CREATE INDEX IF NOT EXISTS idx_interactions_couple ON public.interactions_log(couple_id, created_at DESC);

-- 6. Row Level Security (RLS)
ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.couples ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.interactions_log ENABLE ROW LEVEL SECURITY;

-- Profiles Policies
CREATE POLICY "Public profiles are viewable by authenticated users" 
ON public.profiles FOR SELECT TO authenticated USING (true);

CREATE POLICY "Users can update their own profile" 
ON public.profiles FOR UPDATE TO authenticated USING (auth.uid() = id);

-- Couples Policies
CREATE POLICY "Users can view their own couple room"
ON public.couples FOR SELECT TO authenticated
USING (auth.uid() = user1_id OR auth.uid() = user2_id);

CREATE POLICY "Users can insert a couple room"
ON public.couples FOR INSERT TO authenticated
WITH CHECK (auth.uid() = user1_id);

CREATE POLICY "Partners can join a couple room"
ON public.couples FOR UPDATE TO authenticated
USING (auth.uid() = user1_id OR auth.uid() = user2_id OR user2_id IS NULL);

-- Interactions Log Policies
CREATE POLICY "Couple members can view interactions"
ON public.interactions_log FOR SELECT TO authenticated
USING (
    EXISTS (
        SELECT 1 FROM public.couples c 
        WHERE c.id = couple_id AND (c.user1_id = auth.uid() OR c.user2_id = auth.uid())
    )
);

CREATE POLICY "Couple members can insert interactions"
ON public.interactions_log FOR INSERT TO authenticated
WITH CHECK (
    auth.uid() = sender_id AND
    EXISTS (
        SELECT 1 FROM public.couples c 
        WHERE c.id = couple_id AND (c.user1_id = auth.uid() OR c.user2_id = auth.uid())
    )
);

-- 7. Helper Function: Generate Random 6-Character Pair Code
CREATE OR REPLACE FUNCTION generate_pair_code() 
RETURNS TEXT AS $$
DECLARE
    chars TEXT := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
    result TEXT := '';
    i INT;
BEGIN
    FOR i IN 1..6 LOOP
        result := result || substr(chars, floor(random() * length(chars) + 1)::INT, 1);
    END LOOP;
    RETURN result;
END;
$$ LANGUAGE plpgsql;

-- 8. Enable Realtime Publications on necessary tables
-- (Note: Broadcast channel doesn't require table publication, but Presence and status sync can use it)
ALTER PUBLICATION supabase_realtime ADD TABLE public.profiles;
ALTER PUBLICATION supabase_realtime ADD TABLE public.couples;
