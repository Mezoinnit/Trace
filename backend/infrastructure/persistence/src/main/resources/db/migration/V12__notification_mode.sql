-- Phase 10 audit remediation R3: per-watch notification policy (plan §4.14).
-- IMMEDIATE = current behavior (alert visible in-app; FCM when it ships).
-- DIGEST is reserved for the digest scheduler (FCM phase).
-- NEVER = in-app only, never pushed.

ALTER TABLE watches
    ADD COLUMN notification_mode TEXT NOT NULL DEFAULT 'IMMEDIATE';

ALTER TABLE watches
    ADD CONSTRAINT watches_notification_mode CHECK (notification_mode IN
        ('IMMEDIATE', 'DIGEST', 'NEVER'));
