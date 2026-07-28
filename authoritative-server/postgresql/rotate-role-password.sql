\set ON_ERROR_STOP on
\set QUIET on

SELECT :'role_name' IN (
    'unciv_runtime',
    'unciv_migrate',
    'unciv_backup',
    'unciv_restore',
    'unciv_audit'
) AS allowed
\gset

\if :allowed
SELECT format('ALTER ROLE %I PASSWORD %L', :'role_name', :'new_password')
\gexec
\else
\warn refusing role outside the Unciv credential-rotation allowlist
\quit 3
\endif
