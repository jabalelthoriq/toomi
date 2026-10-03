-- ========================================================
-- Seamless Single Device Session Management (WhatsApp / Telegram style)
-- ========================================================

-- Function to register / claim active device session
-- When user authenticates (via Google / OTP Email), they are verified as owner.
-- This immediately sets the device as active and kicks out any previous device session.
CREATE OR REPLACE FUNCTION public.request_device_session(
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

    -- Update to new active device
    UPDATE public.profiles
    SET active_device_id = p_device_id,
        active_device_name = p_device_name,
        last_device_active_at = NOW()
    WHERE id = v_user_id;

    -- Clean up any pending login requests
    UPDATE public.login_requests
    SET status = 'APPROVED'
    WHERE user_id = v_user_id AND status = 'PENDING';

    RETURN jsonb_build_object(
        'status', 'GRANTED',
        'is_switched', (v_old_device_id IS NOT NULL AND v_old_device_id <> p_device_id),
        'previous_device_name', v_old_device_name,
        'message', 'Perangkat berhasil terhubung sebagai sesi aktif'
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Function to force claim active device session
CREATE OR REPLACE FUNCTION public.force_claim_device_session(
    p_device_id TEXT,
    p_device_name TEXT
)
RETURNS JSONB AS $$
BEGIN
    RETURN public.request_device_session(p_device_id, p_device_name);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Heartbeat function for active device
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
GRANT EXECUTE ON FUNCTION public.request_device_session(TEXT, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.force_claim_device_session(TEXT, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.update_device_heartbeat(TEXT) TO authenticated;
