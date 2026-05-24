-- ============================================================
-- V21__seed_demo_member_accounts.sql
-- Seed default member accounts for local development/demo.
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
          'Demo Member One',
          'member1@library.local',
          '$2a$10$XymL5pLDR6jWttCBmMxLb.t6ClTpNptdSEbBXhrGmoJa.9W3f9Iqu',
          '0900000001',
          'MEMBER',
          'ACTIVE',
          5,
          NOW() + INTERVAL '1 year',
          NOW(),
          NOW()
      ),
      (
          'Demo Member Two',
          'member2@library.local',
          '$2a$10$XymL5pLDR6jWttCBmMxLb.t6ClTpNptdSEbBXhrGmoJa.9W3f9Iqu',
          '0900000002',
          'MEMBER',
          'ACTIVE',
          5,
          NOW() + INTERVAL '1 year',
          NOW(),
          NOW()
      )
ON CONFLICT (email) DO UPDATE
SET password = EXCLUDED.password,
    phone = EXCLUDED.phone,
    role = EXCLUDED.role,
    status = EXCLUDED.status,
    max_borrow_limit = EXCLUDED.max_borrow_limit,
    membership_expires_at = EXCLUDED.membership_expires_at,
    updated_at = NOW();
