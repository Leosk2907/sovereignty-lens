-- Upgrades audience writes from one edge to one atomic company profile.

drop index dependencies_contributor_round_key;

create table contributions (
    id                      uuid primary key default gen_random_uuid(),
    session_id              uuid        not null references sessions (id) on delete cascade,
    round                   integer     not null check (round > 0),
    contributor_hash        text        not null,
    company_organization_id uuid        not null references organizations (id),
    created_at              timestamptz not null default now(),
    constraint contributions_session_round_contributor_key
        unique (session_id, round, contributor_hash)
);

alter table dependencies
    add column contribution_id uuid references contributions (id);

create index contributions_session_round_idx
    on contributions (session_id, round, created_at desc);
create index dependencies_contribution_idx
    on dependencies (contribution_id) where contribution_id is not null;

-- One call creates the European company, every customer-to-company edge, every
-- company-to-provider edge, and one durable live event per edge. Any failure
-- rolls the entire profile and all of its events back.
create or replace function submit_company_profile(
    p_session_slug text,
    p_company jsonb,
    p_customer_ids jsonb,
    p_dependencies jsonb,
    p_contributor_hash text,
    p_round_capacity integer
) returns jsonb
    language plpgsql as
$$
declare
    v_session                 sessions%rowtype;
    v_company                 organizations%rowtype;
    v_customer                organizations%rowtype;
    v_provider                organizations%rowtype;
    v_dependency              dependencies%rowtype;
    v_contribution            contributions%rowtype;
    v_provider_input          jsonb;
    v_customer_id             uuid;
    v_active_count            integer;
    v_customer_count          integer;
    v_provider_count          integer;
    v_valid_customer_count    integer;
    v_event                   jsonb;
    v_company_node            jsonb;
    v_node                    jsonb;
    v_edge                    jsonb;
    v_customer_connections    jsonb := '[]'::jsonb;
    v_dependency_connections  jsonb := '[]'::jsonb;
begin
    if jsonb_typeof(p_company) <> 'object'
       or jsonb_typeof(p_customer_ids) <> 'array'
       or jsonb_typeof(p_dependencies) <> 'array' then
        raise exception 'company profile has an invalid shape' using errcode = 'SL007';
    end if;

    v_customer_count := jsonb_array_length(p_customer_ids);
    v_provider_count := jsonb_array_length(p_dependencies);
    if v_customer_count < 1 or v_customer_count > 3 then
        raise exception 'choose between one and three customers' using errcode = 'SL007';
    end if;
    if v_provider_count < 1 or v_provider_count > 3 then
        raise exception 'choose between one and three dependencies' using errcode = 'SL007';
    end if;
    if p_contributor_hash is null or length(btrim(p_contributor_hash)) = 0 then
        raise exception 'a contributor hash is required' using errcode = 'SL007';
    end if;

    select * into v_session from sessions where slug = p_session_slug for update;
    if not found then
        raise exception 'session not found: %', p_session_slug using errcode = 'SL001';
    end if;
    if v_session.status <> 'open' then
        raise exception 'session is paused' using errcode = 'SL003';
    end if;

    if exists (select 1 from contributions
               where session_id = v_session.id
                 and round = v_session.current_round
                 and contributor_hash = p_contributor_hash)
       or exists (select 1 from dependencies
                  where session_id = v_session.id
                    and round = v_session.current_round
                    and contributor_hash = p_contributor_hash) then
        raise exception 'contributor already submitted in this round' using errcode = 'SL004';
    end if;

    if p_company ->> 'organizationType' = 'government'
       or p_company ->> 'jurisdiction' <> 'europe' then
        raise exception 'the contributed company must be a European company' using errcode = 'SL007';
    end if;
    if exists (select 1 from organizations
               where session_id = v_session.id
                 and normalized_name = p_company ->> 'normalizedName') then
        raise exception 'the contributed company already exists' using errcode = 'SL005';
    end if;

    if (select count(distinct value) from jsonb_array_elements_text(p_customer_ids))
       <> v_customer_count then
        raise exception 'customer organizations must be distinct' using errcode = 'SL007';
    end if;
    if (select count(distinct value ->> 'normalizedName')
        from jsonb_array_elements(p_dependencies)) <> v_provider_count then
        raise exception 'dependency providers must be distinct' using errcode = 'SL007';
    end if;
    if exists (select 1 from jsonb_array_elements(p_dependencies) provider
               where provider ->> 'normalizedName' = p_company ->> 'normalizedName'
                  or provider ->> 'organizationType' = 'government') then
        raise exception 'company and dependency names and types are invalid' using errcode = 'SL007';
    end if;

    with recursive reachable(id) as (
        select v_session.root_organization_id
        union
        select dependency.target_organization_id
        from dependencies dependency
        join reachable source on source.id = dependency.source_organization_id
        where dependency.session_id = v_session.id
          and dependency.status = 'active'
          and (dependency.is_seed or dependency.round = v_session.current_round)
    )
    select count(distinct organization.id)
    into v_valid_customer_count
    from jsonb_array_elements_text(p_customer_ids) requested(id)
    join organizations organization
      on organization.id = requested.id::uuid
     and organization.session_id = v_session.id
     and organization.jurisdiction = 'europe'
    join reachable on reachable.id = organization.id;

    if v_valid_customer_count <> v_customer_count then
        raise exception 'a customer is missing, non-European, or unreachable' using errcode = 'SL002';
    end if;
    if exists (
        select 1
        from jsonb_array_elements_text(p_customer_ids) requested(id)
        join organizations customer on customer.id = requested.id::uuid
        where customer.normalized_name = p_company ->> 'normalizedName'
           or exists (
               select 1 from jsonb_array_elements(p_dependencies) provider
               where provider ->> 'normalizedName' = customer.normalized_name
           )
    ) then
        raise exception 'company, customer, and dependency names must be distinct'
            using errcode = 'SL007';
    end if;

    select count(*) into v_active_count
    from dependencies
    where session_id = v_session.id
      and round = v_session.current_round
      and not is_seed
      and status = 'active';
    if v_active_count + v_customer_count + v_provider_count > p_round_capacity then
        raise exception 'round capacity reached' using errcode = 'SL006';
    end if;

    insert into organizations (
        session_id, name, normalized_name, organization_type, jurisdiction, is_seed)
    values (
        v_session.id,
        p_company ->> 'name',
        p_company ->> 'normalizedName',
        p_company ->> 'organizationType',
        'europe',
        false)
    returning * into v_company;

    insert into contributions (
        session_id, round, contributor_hash, company_organization_id)
    values (
        v_session.id, v_session.current_round, p_contributor_hash, v_company.id)
    returning * into v_contribution;

    v_company_node := jsonb_build_object(
        'id', v_company.id::text,
        'name', v_company.name,
        'organizationType', v_company.organization_type,
        'jurisdiction', v_company.jurisdiction,
        'isSeed', v_company.is_seed);

    for v_customer_id in
        select value::uuid from jsonb_array_elements_text(p_customer_ids)
    loop
        select * into strict v_customer from organizations where id = v_customer_id;
        insert into dependencies (
            session_id, round, source_organization_id, target_organization_id,
            contributor_hash, contribution_id, is_seed, status)
        values (
            v_session.id, v_session.current_round, v_customer.id, v_company.id,
            p_contributor_hash, v_contribution.id, false, 'active')
        returning * into v_dependency;

        v_edge := jsonb_build_object(
            'id', v_dependency.id::text,
            'sourceOrganizationId', v_dependency.source_organization_id::text,
            'targetOrganizationId', v_dependency.target_organization_id::text,
            'isSeed', v_dependency.is_seed,
            'status', v_dependency.status,
            'createdAt', to_char(v_dependency.created_at at time zone 'utc',
                                 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'));
        v_event := emit_graph_event(
            v_session.id,
            v_session.slug,
            v_session.current_round,
            'dependency.created',
            jsonb_build_object('node', v_company_node, 'edge', v_edge));
        v_customer_connections := v_customer_connections || jsonb_build_array(
            jsonb_build_object(
                'eventId', v_event ->> 'eventId',
                'node', v_company_node,
                'edge', v_edge));
    end loop;

    for v_provider_input in select value from jsonb_array_elements(p_dependencies)
    loop
        select * into v_provider
        from organizations
        where session_id = v_session.id
          and normalized_name = v_provider_input ->> 'normalizedName';

        if not found then
            insert into organizations (
                session_id, name, normalized_name, organization_type, jurisdiction, is_seed)
            values (
                v_session.id,
                v_provider_input ->> 'name',
                v_provider_input ->> 'normalizedName',
                v_provider_input ->> 'organizationType',
                v_provider_input ->> 'jurisdiction',
                false)
            returning * into v_provider;
        end if;
        if v_provider.organization_type = 'government' then
            raise exception 'a dependency provider cannot be a public body' using errcode = 'SL007';
        end if;

        insert into dependencies (
            session_id, round, source_organization_id, target_organization_id,
            contributor_hash, contribution_id, is_seed, status)
        values (
            v_session.id, v_session.current_round, v_company.id, v_provider.id,
            p_contributor_hash, v_contribution.id, false, 'active')
        returning * into v_dependency;

        v_node := jsonb_build_object(
            'id', v_provider.id::text,
            'name', v_provider.name,
            'organizationType', v_provider.organization_type,
            'jurisdiction', v_provider.jurisdiction,
            'isSeed', v_provider.is_seed);
        v_edge := jsonb_build_object(
            'id', v_dependency.id::text,
            'sourceOrganizationId', v_dependency.source_organization_id::text,
            'targetOrganizationId', v_dependency.target_organization_id::text,
            'isSeed', v_dependency.is_seed,
            'status', v_dependency.status,
            'createdAt', to_char(v_dependency.created_at at time zone 'utc',
                                 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'));
        v_event := emit_graph_event(
            v_session.id,
            v_session.slug,
            v_session.current_round,
            'dependency.created',
            jsonb_build_object('node', v_node, 'edge', v_edge));
        v_dependency_connections := v_dependency_connections || jsonb_build_array(
            jsonb_build_object(
                'eventId', v_event ->> 'eventId',
                'node', v_node,
                'edge', v_edge));
    end loop;

    return jsonb_build_object(
        'round', v_session.current_round,
        'company', v_company_node,
        'customerConnections', v_customer_connections,
        'dependencyConnections', v_dependency_connections);
exception
    when unique_violation then
        if sqlerrm like '%contributions_session_round_contributor_key%' then
            raise exception 'contributor already submitted in this round' using errcode = 'SL004';
        end if;
        raise exception 'a company or dependency already exists' using errcode = 'SL005';
end;
$$;
