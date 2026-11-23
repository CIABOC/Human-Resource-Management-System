ALTER TABLE app_user
    ADD COLUMN preferred_theme VARCHAR(10) NOT NULL DEFAULT 'LIGHT'
        CHECK (preferred_theme IN ('LIGHT', 'DARK')),
    ADD COLUMN notify_system BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN notify_email BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN notify_sms BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN profile_photo_file_id BIGINT REFERENCES stored_file (id),
    ADD COLUMN token_version INTEGER NOT NULL DEFAULT 0;

CREATE INDEX ix_refresh_token_active_user
    ON refresh_token (user_id, expires_at)
    WHERE revoked = FALSE;

ALTER TABLE profile_change_request
    ADD COLUMN request_type VARCHAR(40) NOT NULL DEFAULT 'PROFILE_CHANGE',
    ADD COLUMN request_details TEXT;
