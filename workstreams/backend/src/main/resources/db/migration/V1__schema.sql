-- Sovereignty Lens core schema.
--
-- All data stored here is simulated, unverified demo data produced by a live
-- audience. It must never be presented as a factual allegation.

create table sessions (
    id                   uuid primary key     default gen_random_uuid(),
    slug                 text        not null unique,
    title                text        not null,
    status               text        not null default 'open'
        constraint sessions_status_check check (status in ('open', 'paused')),
    current_round        integer     not null default 1
        constraint sessions_round_positive_check check (current_round > 0),
    root_organization_id uuid,
    created_at           timestamptz not null default now(),
    updated_at           timestamptz not null default now()
);

create table organizations (
    id                uuid primary key     default gen_random_uuid(),
    session_id        uuid        not null references sessions (id) on delete cascade,
    name              text        not null,
    normalized_name   text        not null,
    organization_type text        not null
        constraint organizations_type_check check (organization_type in
            ('government', 'cloud', 'software', 'hardware', 'telecom',
             'consulting', 'logistics', 'finance', 'other')),
    jurisdiction      text        not null
        constraint organizations_jurisdiction_check check (jurisdiction in
            ('europe', 'united_states', 'china', 'other_external', 'unknown')),
    is_seed           boolean     not null default false,
    created_at        timestamptz not null default now(),
    -- A normalized organization name is unique inside a session, which is what
    -- makes two audience members naming the same company reuse one node.
    constraint organizations_session_normalized_name_key unique (session_id, normalized_name)
);

-- Added after organizations exists because the two tables reference each other.
alter table sessions
    add constraint sessions_root_organization_fk
        foreign key (root_organization_id) references organizations (id);

create table dependencies (
    id                     uuid primary key     default gen_random_uuid(),
    session_id             uuid        not null references sessions (id) on delete cascade,
    -- Seed rows use null and stay visible in every round; audience rows belong
    -- to exactly one positive round.
    round                  integer,
    source_organization_id uuid        not null references organizations (id),
    target_organization_id uuid        not null references organizations (id),
    -- Keyed HMAC of the browser's anonymous id. The raw id is never stored.
    contributor_hash       text,
    is_seed                boolean     not null default false,
    status                 text        not null default 'active'
        constraint dependencies_status_check check (status in ('active', 'hidden')),
    created_at             timestamptz not null default now(),
    constraint dependencies_no_self_reference_check
        check (source_organization_id <> target_organization_id),
    -- An audience row must carry a contributor hash. The once-per-round unique
    -- index below is filtered on `contributor_hash is not null`, so a null hash
    -- would silently escape the one-submission-per-browser rule entirely.
    constraint dependencies_seed_shape_check
        check ((is_seed and round is null and contributor_hash is null)
            or (not is_seed and round is not null and round > 0
                and contributor_hash is not null))
);

-- One source/target edge is active at most once per session and round. Seed rows
-- collapse into round bucket 0 so they cannot be duplicated either.
create unique index dependencies_active_edge_key
    on dependencies (session_id, coalesce(round, 0), source_organization_id, target_organization_id)
    where status = 'active';

-- A browser contributes at most once per session round. Deliberately not
-- filtered by status: hiding or undoing a contribution does not hand the same
-- browser another submission in the same round.
create unique index dependencies_contributor_round_key
    on dependencies (session_id, round, contributor_hash)
    where contributor_hash is not null;

create index dependencies_session_round_idx on dependencies (session_id, round, status);
create index dependencies_session_created_idx on dependencies (session_id, created_at desc, id desc);
create index organizations_session_idx on organizations (session_id);

-- Durable log of every live event. This is what makes a committed contribution
-- and its live event atomic: both rows are written in the same transaction.
-- It also lets a reconnecting Server-Sent Events client resume from Last-Event-ID.
create table graph_events (
    id           uuid primary key     default gen_random_uuid(),
    sequence     bigserial   not null,
    session_id   uuid        not null references sessions (id) on delete cascade,
    session_slug text        not null,
    round        integer     not null,
    event_type   text        not null
        constraint graph_events_type_check
            check (event_type in ('dependency.created', 'graph.invalidated')),
    payload      jsonb       not null,
    occurred_at  timestamptz not null default now()
);

create index graph_events_slug_sequence_idx on graph_events (session_slug, sequence);
create unique index graph_events_sequence_key on graph_events (sequence);

create or replace function touch_updated_at() returns trigger
    language plpgsql as
$$
begin
    new.updated_at := now();
    return new;
end;
$$;

create trigger sessions_touch_updated_at
    before update
    on sessions
    for each row
execute function touch_updated_at();
