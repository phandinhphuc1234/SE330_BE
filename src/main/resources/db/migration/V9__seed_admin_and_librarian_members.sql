-- ============================================================
-- V9__seed_admin_and_librarian_members.sql
-- Seed default staff accounts for local development/demo.
--
-- Login password for both accounts: Password123
-- Change these credentials before using the system in production.
-- ============================================================

INSERT INTO members (
    full_name,
    email,
    password,
    phone,
    role,
    status,
    max_borrow_limit,
    membership_expires_at,
    created_at,
    updated_at
) VALUES
      (
          'System Admin',
          'admin@library.local',
          '$2a$10$XymL5pLDR6jWttCBmMxLb.t6ClTpNptdSEbBXhrGmoJa.9W3f9Iqu',
          NULL,
          'ADMIN',
          'ACTIVE',
          20,
          NULL,
          NOW(),
          NOW()
      ),
      (
          'Library Staff',
          'librarian@library.local',
          '$2a$10$ALv18cx5qHfjw0CnI.WK2.JgVyG8ISP5yEvyrx.jVVIhNNufBMf6S',
          NULL,
          'LIBRARIAN',
          'ACTIVE',
          15,
          NULL,
          NOW(),
          NOW()
      )
ON CONFLICT (email) DO UPDATE
SET password = EXCLUDED.password,
    role = EXCLUDED.role,
    status = EXCLUDED.status,
    max_borrow_limit = EXCLUDED.max_borrow_limit,
    updated_at = NOW();
