-- ========================================================
-- Fix Device Session Flow & Force Takeover
-- ========================================================

-- 1. Function to force claim / switch device session
CREATE OR REPLACE FUNCTION public.force_claim_device_session(
    p_device_id TEXT,
    p_device_name TEXT
)
RETURNS JSONB AS $$
DECLARE
    v_user_id UUID := auth.uid();
    v_old_device_id TEXT;
    v_old_device_name TEXT;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    SELECT active_device_id, active_device_name
    INTO v_old_device_id, v_old_device_name
    FROM public.profiles
    WHERE id = v_user_id;

    -- Update active device in profiles
    UPDATE public.profiles
    SET active_device_id = p_device_id,
        active_device_name = p_device_name,
        last_device_active_at = NOW()
    WHERE id = v_user_id;

    -- Mark all pending login requests as approved
    UPDATE public.login_requests
    SET status = 'APPROVED'
    WHERE user_id = v_user_id AND status = 'PENDING';

    RETURN jsonb_build_object(
        'status', 'GRANTED',
        'previous_device_id', v_old_device_id,
        'previous_device_name', v_old_device_name,
        'message', 'Sesi perangkat berhasil dialihkan ke HP ini'
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- 2. Enhanced request_device_session with auto-expiry check
CREATE OR REPLACE FUNCTION public.request_device_session(
    p_device_id TEXT,
    p_device_name TEXT
)
RETURNS JSONB AS $$
DECLARE
    v_user_id UUID := auth.uid();
    v_current_device_id TEXT;
    v_current_device_name TEXT;
    v_last_active TIMESTAMPTZ;
    v_req_id UUID;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    SELECT active_device_id, active_device_name, last_device_active_at
    INTO v_current_device_id, v_current_device_name, v_last_active
    FROM public.profiles 
    WHERE id = v_user_id;

    -- If no active device or same device, grant immediately
    IF v_current_device_id IS NULL OR v_current_device_id = p_device_id THEN
        UPDATE public.profiles
        SET active_device_id = p_device_id,
            active_device_name = p_device_name,
            last_device_active_at = NOW()
        WHERE id = v_user_id;

        RETURN jsonb_build_object(
            'status', 'GRANTED',
            'message', 'Perangkat berhasil didaftarkan sebagai sesi aktif'
        );
    END IF;

    -- If the old device has not been active for > 10 minutes, auto-grant immediately
    IF v_last_active IS NULL OR v_last_active < (NOW() - INTERVAL '10 minutes') THEN
        UPDATE public.profiles
        SET active_device_id = p_device_id,
            active_device_name = p_device_name,
            last_device_active_at = NOW()
        WHERE id = v_user_id;

        RETURN jsonb_build_object(
            'status', 'GRANTED',
            'message', 'Sesi perangkat lama tidak aktif. Sesi otomatis dialihkan.'
        );
    END IF;

    -- Create pending request
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
        'message', 'Menunggu persetujuan dari perangkat aktif'
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- 3. Heartbeat function for active device
CREATE OR REPLACE FUNCTION public.update_device_heartbeat(
    p_device_id TEXT
)
RETURNS VOID AS $$
DECLARE
    v_user_id UUID := auth.uid();
BEGIN
    IF v_user_id IS NOT NULL THEN
        UPDATE public.profiles
        SET last_device_active_at = NOW()
        WHERE id = v_user_id AND active_device_id = p_device_id;
    END IF;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Grant execute permissions to authenticated users
GRANT EXECUTE ON FUNCTION public.force_claim_device_session(TEXT, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.request_device_session(TEXT, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.update_device_heartbeat(TEXT) TO authenticated;
