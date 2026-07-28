\set ON_ERROR_STOP on
\set QUIET on

SELECT 'CREATE ROLE unciv_runtime LOGIN'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'unciv_runtime')
\gexec
SELECT 'CREATE ROLE unciv_migrate LOGIN'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'unciv_migrate')
\gexec
SELECT 'CREATE ROLE unciv_backup LOGIN'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'unciv_backup')
\gexec
SELECT 'CREATE ROLE unciv_restore LOGIN'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'unciv_restore')
\gexec
SELECT 'CREATE ROLE unciv_audit LOGIN'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'unciv_audit')
\gexec

ALTER ROLE unciv_runtime
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
    CONNECTION LIMIT 20 PASSWORD :'runtime_password';
ALTER ROLE unciv_migrate
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
    CONNECTION LIMIT 2 PASSWORD :'migration_password';
ALTER ROLE unciv_backup
    NOSUPERUSER NOCREATEDB NOCREATEROLE REPLICATION NOBYPASSRLS
    CONNECTION LIMIT 2 PASSWORD :'backup_password';
ALTER ROLE unciv_restore
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
    CONNECTION LIMIT 1 PASSWORD :'restore_password';
ALTER ROLE unciv_audit
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
    CONNECTION LIMIT 2 PASSWORD :'audit_password';

ALTER ROLE unciv_runtime SET statement_timeout = '15s';
ALTER ROLE unciv_runtime SET lock_timeout = '5s';
ALTER ROLE unciv_migrate SET statement_timeout = '5min';
ALTER ROLE unciv_migrate SET lock_timeout = '30s';
ALTER ROLE unciv_audit SET default_transaction_read_only = on;
ALTER ROLE unciv_audit SET statement_timeout = '30s';
ALTER ROLE unciv_audit SET lock_timeout = '5s';

REVOKE ALL ON DATABASE unciv_authoritative FROM PUBLIC;
REVOKE ALL ON DATABASE unciv_authoritative FROM
    unciv_runtime, unciv_migrate, unciv_backup, unciv_restore, unciv_audit;
GRANT CONNECT ON DATABASE unciv_authoritative TO
    unciv_runtime, unciv_migrate, unciv_audit;
GRANT TEMPORARY ON DATABASE unciv_authoritative TO unciv_migrate;
ALTER DATABASE unciv_authoritative OWNER TO unciv_migrate;

\connect unciv_authoritative

REVOKE ALL ON SCHEMA public FROM PUBLIC;
ALTER SCHEMA public OWNER TO unciv_migrate;
GRANT USAGE ON SCHEMA public TO unciv_runtime, unciv_audit;

ALTER DEFAULT PRIVILEGES FOR ROLE unciv_migrate IN SCHEMA public
    REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE unciv_migrate IN SCHEMA public
    REVOKE ALL ON SEQUENCES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE unciv_migrate IN SCHEMA public
    REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE unciv_migrate IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO unciv_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE unciv_migrate IN SCHEMA public
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO unciv_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE unciv_migrate IN SCHEMA public
    GRANT SELECT ON TABLES TO unciv_audit;
ALTER DEFAULT PRIVILEGES FOR ROLE unciv_migrate IN SCHEMA public
    GRANT SELECT ON SEQUENCES TO unciv_audit;

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public
    TO unciv_runtime;
SELECT 'REVOKE UPDATE, DELETE, TRUNCATE ON security_audit_events FROM unciv_runtime'
WHERE to_regclass('public.security_audit_events') IS NOT NULL
\gexec
SELECT 'GRANT SELECT, INSERT ON security_audit_events TO unciv_runtime'
WHERE to_regclass('public.security_audit_events') IS NOT NULL
\gexec
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public
    TO unciv_runtime;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO unciv_audit;
GRANT SELECT ON ALL SEQUENCES IN SCHEMA public TO unciv_audit;
REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA public FROM PUBLIC;
