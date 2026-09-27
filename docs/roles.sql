-- Least-privilege reference roles for a dedicated Microservices Validator database.
-- Login roles are environment-specific and should be granted one or more NOLOGIN roles below.

BEGIN;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'msval_reader') THEN
        CREATE ROLE msval_reader NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'msval_runtime') THEN
        CREATE ROLE msval_runtime NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'msval_policy_admin') THEN
        CREATE ROLE msval_policy_admin NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'msval_maintenance') THEN
        CREATE ROLE msval_maintenance NOLOGIN;
    END IF;
END;
$$;

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO msval_reader, msval_runtime, msval_policy_admin, msval_maintenance;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO msval_reader;
GRANT msval_reader TO msval_runtime, msval_policy_admin, msval_maintenance;

GRANT INSERT ON
    service_versions, architecture_declarations, declared_edges,
    artifact_objects, policy_resolution_events,
    validation_runs, validation_results,
    decision_context_snapshots, decision_context_waivers, decisions, decision_results,
    deployments, findings, finding_events, waiver_requests, waivers, waiver_events,
    scheduled_jobs, outbox_events, idempotency_records, audit_events,
    observations, observed_edges
TO msval_runtime;

GRANT UPDATE ON
    architecture_declarations, artifact_objects, findings, waiver_requests, waivers,
    scheduled_jobs, outbox_events
TO msval_runtime;

GRANT INSERT, UPDATE ON estate_scopes, services, service_scope_grants TO msval_policy_admin;
GRANT DELETE ON service_scope_grants TO msval_policy_admin;
GRANT INSERT ON artifact_objects, policy_artifacts, policy_activations, audit_events, outbox_events, idempotency_records
TO msval_policy_admin;
GRANT UPDATE ON artifact_objects, policy_activations, outbox_events TO msval_policy_admin;

GRANT UPDATE ON scheduled_jobs, outbox_events TO msval_maintenance;
GRANT DELETE ON scheduled_jobs, outbox_events, idempotency_records, artifact_objects TO msval_maintenance;

COMMIT;
