-- Transactional submission and live-event emission.
--
-- Domain failures raise custom SQLSTATEs in the SL### class. The application
-- maps them one-to-one onto the canonical ApiErrorCode values, so an error can
-- never be reported with a status the data contract does not allow:
--
--   SL001 SESSION_NOT_FOUND        SL005 DUPLICATE_DEPENDENCY
--   SL002 SOURCE_NOT_FOUND         SL006 ROUND_CAPACITY_REACHED
--   SL003 SESSION_PAUSED           SL007 VALIDATION_ERROR
--   SL004 ALREADY_CONTRIBUTED

-- Writes one durable event row and wakes listeners. Called inside the caller's
-- transaction, so a rollback discards the event exactly as it discards the data.
-- pg_notify is itself transactional: listeners are woken only on commit.
create or replace function emit_graph_event(
    p_session_id uuid,
    p_session_slug text,
    p_round integer,
    p_event_type text,
    p_body jsonb
) returns jsonb
    language plpgsql as
$$
declare
    v_event_id uuid        := gen_random_uuid();
    v_occurred timestamptz := now();
    v_payload  jsonb;
begin
    v_payload := jsonb_build_object(
                         'contractVersion', 1,
                         'event', p_event_type,
                         'eventId', v_event_id::text,
                         'sessionSlug', p_session_slug,
                         'round', p_round,
                         'occurredAt',
                         to_char(v_occurred at time zone 'utc', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')
                 ) || coalesce(p_body, '{}'::jsonb);

    insert into graph_events (id, session_id, session_slug, round, event_type, payload, occurred_at)
    values (v_event_id, p_session_id, p_session_slug, p_round, p_event_type, v_payload, v_occurred);

    perform pg_notify('sovereignty_graph_events', v_event_id::text);

    return v_payload;
end;
$$;

-- Atomic audience submission.
--
-- Validates the session and source, reuses or creates the target organization,
-- inserts the dependency, and emits the canonical dependency.created event, all
-- in one transaction. The caller supplies the already-normalized display name
-- and comparison key so that Unicode normalization stays unit-testable in the
-- application layer.
create or replace function submit_dependency(
    p_session_slug text,
    p_source_organization_id uuid,
    p_target_name text,
    p_target_normalized_name text,
    p_target_organization_type text,
    p_target_jurisdiction text,
    p_contributor_hash text,
    p_round_capacity integer
) returns jsonb
    language plpgsql as
$$
declare
    v_session      sessions%rowtype;
    v_source       organizations%rowtype;
    v_target       organizations%rowtype;
    v_dependency   dependencies%rowtype;
    v_active_count integer;
    v_event        jsonb;
    v_node         jsonb;
    v_edge         jsonb;
begin
    -- Serializes concurrent submissions for this session so the capacity check
    -- and the duplicate checks below see a stable view.
    select * into v_session from sessions where slug = p_session_slug for update;
    if not found then
        raise exception 'session not found: %', p_session_slug using errcode = 'SL001';
    end if;

    if v_session.status <> 'open' then
        raise exception 'session is paused' using errcode = 'SL003';
    end if;

    select * into v_source
    from organizations
    where id = p_source_organization_id
      and session_id = v_session.id;
    if not found then
        raise exception 'source organization not found' using errcode = 'SL002';
    end if;

    if p_target_organization_type = 'government' then
        raise exception 'target organization type may not be government' using errcode = 'SL007';
    end if;

    -- Checked here rather than trusted from the caller. The duplicate-contributor
    -- test below compares with `=`, which is never true for a null, and the
    -- unique index is filtered on a non-null hash: a null would therefore submit
    -- without limit. This function is documented as the layer that enforces the
    -- one-submission-per-browser rule, so it has to hold on its own.
    if p_contributor_hash is null or length(btrim(p_contributor_hash)) = 0 then
        raise exception 'a contributor hash is required' using errcode = 'SL007';
    end if;

    if exists (select 1
               from dependencies
               where session_id = v_session.id
                 and round = v_session.current_round
                 and contributor_hash = p_contributor_hash) then
        raise exception 'contributor already submitted in this round' using errcode = 'SL004';
    end if;

    -- Hidden rows do not consume capacity, so a presenter who hides spam gives
    -- the round its slot back.
    select count(*)
    into v_active_count
    from dependencies
    where session_id = v_session.id
      and round = v_session.current_round
      and not is_seed
      and status = 'active';

    if v_active_count >= p_round_capacity then
        raise exception 'round capacity reached' using errcode = 'SL006';
    end if;

    -- Reuse an existing organization with the same normalized name, otherwise
    -- create it. Reuse keeps the row's original display name and type.
    select * into v_target
    from organizations
    where session_id = v_session.id
      and normalized_name = p_target_normalized_name;

    if not found then
        insert into organizations (session_id, name, normalized_name, organization_type,
                                   jurisdiction, is_seed)
        values (v_session.id, p_target_name, p_target_normalized_name,
                p_target_organization_type, p_target_jurisdiction, false)
        returning * into v_target;
    end if;

    if v_target.id = v_source.id then
        raise exception 'an organization cannot depend on itself' using errcode = 'SL007';
    end if;

    if exists (select 1
               from dependencies
               where session_id = v_session.id
                 and coalesce(round, 0) in (0, v_session.current_round)
                 and source_organization_id = v_source.id
                 and target_organization_id = v_target.id
                 and status = 'active') then
        raise exception 'this dependency already exists' using errcode = 'SL005';
    end if;

    begin
        insert into dependencies (session_id, round, source_organization_id, target_organization_id,
                                  contributor_hash, is_seed, status)
        values (v_session.id, v_session.current_round, v_source.id, v_target.id,
                p_contributor_hash, false, 'active')
        returning * into v_dependency;
    exception
        when unique_violation then
            -- Belt and braces: the explicit checks above already cover these,
            -- but the partial unique indexes are the real guarantee.
            if sqlerrm like '%dependencies_contributor_round_key%' then
                raise exception 'contributor already submitted in this round' using errcode = 'SL004';
            end if;
            raise exception 'this dependency already exists' using errcode = 'SL005';
    end;

    v_node := jsonb_build_object(
            'id', v_target.id::text,
            'name', v_target.name,
            'organizationType', v_target.organization_type,
            'jurisdiction', v_target.jurisdiction,
            'isSeed', v_target.is_seed);

    -- Microsecond precision, matching what the snapshot path reads straight from
    -- the timestamptz column. Truncating here would give one edge two different
    -- createdAt strings depending on which endpoint returned it.
    v_edge := jsonb_build_object(
            'id', v_dependency.id::text,
            'sourceOrganizationId', v_dependency.source_organization_id::text,
            'targetOrganizationId', v_dependency.target_organization_id::text,
            'isSeed', v_dependency.is_seed,
            'status', v_dependency.status,
            'createdAt', to_char(v_dependency.created_at at time zone 'utc',
                                 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'));

    v_event := emit_graph_event(v_session.id, v_session.slug, v_session.current_round,
                                'dependency.created',
                                jsonb_build_object('node', v_node, 'edge', v_edge));

    return jsonb_build_object(
            'eventId', v_event ->> 'eventId',
            'round', v_session.current_round,
            'node', v_node,
            'edge', v_edge,
            'event', v_event);
end;
$$;
