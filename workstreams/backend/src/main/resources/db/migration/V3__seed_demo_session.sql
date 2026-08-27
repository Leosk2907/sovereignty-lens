-- Fictional seed scenario for the demo session.
--
-- Every organization here is invented. Seed dependencies are European only: the
-- external dependency reveal must come from audience participation, never from
-- data the presenter planted.
--
-- Identifiers are fixed literals so fixtures, contract tests and rehearsal
-- scripts can reference them without querying first.

-- The two tables reference each other, and both foreign keys are immediate.
-- Only one order works: the session first with no root, then its organizations,
-- then the root pointer. sessions.root_organization_id is nullable precisely so
-- this sequence is possible.
insert into sessions (id, slug, title, status, current_round, root_organization_id)
values ('00000000-0000-4000-8000-000000000001', 'demo',
        'Sovereignty Lens live demo', 'open', 1, null)
on conflict (slug) do nothing;

insert into organizations (id, session_id, name, normalized_name, organization_type, jurisdiction,
                           is_seed)
values ('00000000-0000-4000-8000-000000000101', '00000000-0000-4000-8000-000000000001',
        'European Digital Services Agency', 'european digital services agency', 'government',
        'europe', true)
on conflict do nothing;

update sessions
set root_organization_id = '00000000-0000-4000-8000-000000000101'
where id = '00000000-0000-4000-8000-000000000001'
  and root_organization_id is null;

insert into organizations (id, session_id, name, normalized_name, organization_type, jurisdiction,
                           is_seed)
values ('00000000-0000-4000-8000-000000000102', '00000000-0000-4000-8000-000000000001',
        'Alpine Civic Systems', 'alpine civic systems', 'software', 'europe', true),
       ('00000000-0000-4000-8000-000000000103', '00000000-0000-4000-8000-000000000001',
        'Baltic Data Works', 'baltic data works', 'cloud', 'europe', true),
       ('00000000-0000-4000-8000-000000000104', '00000000-0000-4000-8000-000000000001',
        'Rhine Public Networks', 'rhine public networks', 'telecom', 'europe', true)
on conflict do nothing;

-- Seed edges read as "source depends on target" and carry a null round, which
-- keeps them visible in every round.
insert into dependencies (id, session_id, round, source_organization_id, target_organization_id,
                          contributor_hash, is_seed, status)
values ('00000000-0000-4000-8000-000000000201', '00000000-0000-4000-8000-000000000001', null,
        '00000000-0000-4000-8000-000000000101', '00000000-0000-4000-8000-000000000102',
        null, true, 'active'),
       ('00000000-0000-4000-8000-000000000202', '00000000-0000-4000-8000-000000000001', null,
        '00000000-0000-4000-8000-000000000101', '00000000-0000-4000-8000-000000000104',
        null, true, 'active'),
       ('00000000-0000-4000-8000-000000000203', '00000000-0000-4000-8000-000000000001', null,
        '00000000-0000-4000-8000-000000000102', '00000000-0000-4000-8000-000000000103',
        null, true, 'active')
on conflict do nothing;
