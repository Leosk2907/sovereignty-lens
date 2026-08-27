-- Creates the least-privilege role the application connects as.
--
-- Runs once, from the postgres image's entrypoint, before the API container is
-- allowed to start. It has to happen here rather than in a Flyway migration
-- because the connection pool opens as this role at startup: the role must
-- already exist by then.
--
-- Division of labour:
--   POSTGRES_USER (the owner) runs Flyway and owns every table.
--   sovereignty_app runs the service and can only read and write rows.
--
-- The application therefore cannot drop a table, alter a column, or read
-- another database, which is what limits the blast radius of a bug in a service
-- that accepts free text from an anonymous audience.

-- The only value that has to come from outside. The entrypoint runs this file
-- through psql, so a backtick command substitution reads the container's
-- environment.
\set app_password `echo "$APP_DB_PASSWORD"`

do $$
begin
    if not exists (select 1 from pg_roles where rolname = 'sovereignty_app') then
        create role sovereignty_app login;
    end if;
end
$$;

alter role sovereignty_app with password :'app_password';

-- Dynamic SQL because the database and owner names are deployment-specific and
-- cannot be parameterised as identifiers in plain DDL.
do $$
begin
    execute format('grant connect on database %I to sovereignty_app', current_database());

    -- Tables do not exist yet - Flyway creates them on the first API start - so
    -- the grants are expressed as defaults that apply to whatever the owner
    -- creates later. Deliberately no DELETE and no TRUNCATE: a round reset
    -- increments a counter, it never removes audience data.
    execute format(
        'alter default privileges for role %I in schema public '
        'grant select, insert, update on tables to sovereignty_app', current_user);
    execute format(
        'alter default privileges for role %I in schema public '
        'grant usage, select on sequences to sovereignty_app', current_user);
    execute format(
        'alter default privileges for role %I in schema public '
        'grant execute on functions to sovereignty_app', current_user);
end
$$;

grant usage on schema public to sovereignty_app;

-- Covers anything already present, so re-running against a populated volume is
-- safe.
grant select, insert, update on all tables in schema public to sovereignty_app;
grant usage, select on all sequences in schema public to sovereignty_app;
grant execute on all functions in schema public to sovereignty_app;
