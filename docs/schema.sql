-- Microservices Validator reference PostgreSQL schema
-- Target: clean-database design for MVPs 2-5.
-- Production delivery uses ordered Flyway SQL migrations from the Java Governance Plane;
-- this file is the normative end-state model.

BEGIN;

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE estate_scopes (
    scope_id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    display_name        text NOT NULL CHECK (length(display_name) BETWEEN 1 AND 200),
    environment         text NOT NULL CHECK (length(environment) BETWEEN 1 AND 64),
    cluster             text NULL CHECK (cluster IS NULL OR length(cluster) <= 128),
    region              text NULL CHECK (region IS NULL OR length(region) <= 64),
    tenant              text NULL CHECK (tenant IS NULL OR length(tenant) <= 128),
    is_active           boolean NOT NULL DEFAULT true,
    deactivated_at      timestamptz NULL,
    deactivated_by      text NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_estate_scope_tuple UNIQUE NULLS NOT DISTINCT (environment, cluster, region, tenant),
    CONSTRAINT ck_estate_scope_lifecycle CHECK (
        (is_active AND deactivated_at IS NULL AND deactivated_by IS NULL) OR
        (NOT is_active AND deactivated_at IS NOT NULL AND deactivated_by IS NOT NULL)
    )
);

CREATE TABLE services (
    service_id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    service_key         text NOT NULL CHECK (
                            length(service_key) BETWEEN 3 AND 200 AND
                            service_key ~ '^[a-z0-9][a-z0-9._/-]*[a-z0-9]$'
                        ),
    display_name        text NULL CHECK (display_name IS NULL OR length(display_name) <= 300),
    owner_key           text NOT NULL CHECK (length(owner_key) BETWEEN 1 AND 200),
    repository_provider text NOT NULL CHECK (repository_provider IN ('GITHUB','GITLAB','AZURE_DEVOPS','BITBUCKET','OTHER')),
    repository_org      text NOT NULL CHECK (length(repository_org) BETWEEN 1 AND 200),
    repository_name     text NOT NULL CHECK (length(repository_name) BETWEEN 1 AND 200),
    repository_url      text NULL,
    labels              jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(labels) = 'object'),
    is_active           boolean NOT NULL DEFAULT true,
    deactivated_at      timestamptz NULL,
    deactivated_by      text NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_service_key UNIQUE (service_key),
    CONSTRAINT uq_service_repository UNIQUE (repository_provider, repository_org, repository_name),
    CONSTRAINT ck_service_lifecycle CHECK (
        (is_active AND deactivated_at IS NULL AND deactivated_by IS NULL) OR
        (NOT is_active AND deactivated_at IS NOT NULL AND deactivated_by IS NOT NULL)
    )
);

CREATE TABLE service_scope_grants (
    service_id          uuid NOT NULL REFERENCES services(service_id) ON DELETE CASCADE,
    scope_id            uuid NOT NULL REFERENCES estate_scopes(scope_id) ON DELETE RESTRICT,
    granted_by          text NOT NULL CHECK (length(granted_by) BETWEEN 1 AND 500),
    granted_at          timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (service_id, scope_id)
);

CREATE TABLE service_versions (
    service_version_id  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    service_id          uuid NOT NULL REFERENCES services(service_id) ON DELETE RESTRICT,
    version             text NOT NULL CHECK (length(version) BETWEEN 1 AND 200),
    source_commit       text NOT NULL CHECK (source_commit ~ '^[a-fA-F0-9]{40,64}$'),
    artifact_digest     text NOT NULL CHECK (artifact_digest ~ '^sha256:[a-f0-9]{64}$'),
    created_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_service_version UNIQUE (service_id, version),
    CONSTRAINT uq_service_artifact UNIQUE (service_id, artifact_digest),
    CONSTRAINT uq_service_version_identity UNIQUE (service_version_id, service_id, artifact_digest)
);

CREATE TABLE architecture_declarations (
    declaration_id      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    service_id          uuid NOT NULL REFERENCES services(service_id) ON DELETE RESTRICT,
    api_version         text NOT NULL,
    source_commit       text NOT NULL CHECK (source_commit ~ '^[a-fA-F0-9]{40,64}$'),
    source_ref_type     text NOT NULL CHECK (source_ref_type IN ('BRANCH','TAG','PULL_REQUEST')),
    source_ref_name     text NOT NULL CHECK (length(source_ref_name) BETWEEN 1 AND 512),
    pull_request_number bigint NULL CHECK (pull_request_number IS NULL OR pull_request_number > 0),
    digest              text NOT NULL CHECK (digest ~ '^sha256:[a-f0-9]{64}$'),
    document            jsonb NOT NULL CHECK (jsonb_typeof(document) = 'object'),
    is_current          boolean NOT NULL DEFAULT true,
    published_by        text NOT NULL CHECK (length(published_by) BETWEEN 1 AND 500),
    published_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_declaration_digest UNIQUE (service_id, digest)
);

CREATE UNIQUE INDEX uq_current_declaration_per_service
    ON architecture_declarations(service_id)
    WHERE is_current;

CREATE TABLE declared_edges (
    declared_edge_id    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    declaration_id      uuid NOT NULL REFERENCES architecture_declarations(declaration_id) ON DELETE CASCADE,
    source_service_id   uuid NOT NULL REFERENCES services(service_id) ON DELETE RESTRICT,
    target_service_key  text NOT NULL CHECK (length(target_service_key) BETWEEN 3 AND 200),
    protocol            text NULL CHECK (protocol IS NULL OR length(protocol) <= 50),
    purpose             text NULL CHECK (purpose IS NULL OR length(purpose) <= 500),
    attributes          jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(attributes) = 'object'),
    CONSTRAINT uq_declared_edge UNIQUE NULLS NOT DISTINCT (declaration_id, target_service_key, protocol, purpose)
);

-- Controlled content-addressed object registration. Clients never register arbitrary fetch URLs.
CREATE TABLE artifact_objects (
    artifact_object_id  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    kind                text NOT NULL CHECK (kind IN (
                            'POLICY','RENDERED_MANIFEST','ARCHITECTURE_DECLARATION',
                            'DECISION_CONTEXT','RUNTIME_OBSERVATION'
                        )),
    digest              text NOT NULL CHECK (digest ~ '^sha256:[a-f0-9]{64}$'),
    media_type          text NOT NULL CHECK (length(media_type) BETWEEN 1 AND 200),
    size_bytes          bigint NOT NULL CHECK (size_bytes > 0),
    storage_key         text NOT NULL CHECK (length(storage_key) BETWEEN 1 AND 2000),
    status              text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','VERIFIED','REJECTED')),
    created_by          text NOT NULL CHECK (length(created_by) BETWEEN 1 AND 500),
    created_at          timestamptz NOT NULL DEFAULT now(),
    verified_at         timestamptz NULL,
    rejection_reason    text NULL,
    CONSTRAINT uq_artifact_object_digest_kind UNIQUE (digest, kind),
    CONSTRAINT uq_artifact_storage_key UNIQUE (storage_key),
    CONSTRAINT ck_artifact_verification CHECK (
        (status = 'PENDING' AND verified_at IS NULL AND rejection_reason IS NULL) OR
        (status = 'VERIFIED' AND verified_at IS NOT NULL AND rejection_reason IS NULL) OR
        (status = 'REJECTED' AND rejection_reason IS NOT NULL)
    )
);

-- Policy lifecycle and central activation.
CREATE TABLE policy_artifacts (
    policy_artifact_id  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    version             text NOT NULL CHECK (length(version) BETWEEN 1 AND 100),
    digest              text NOT NULL CHECK (digest ~ '^sha256:[a-f0-9]{64}$'),
    media_type          text NOT NULL DEFAULT 'application/vnd.msval.policy+gzip',
    artifact_object_id  uuid NOT NULL UNIQUE REFERENCES artifact_objects(artifact_object_id) ON DELETE RESTRICT,
    evaluator_abi       text NOT NULL CHECK (length(evaluator_abi) BETWEEN 1 AND 100),
    source_repository   text NULL,
    source_commit       text NOT NULL CHECK (source_commit ~ '^[a-fA-F0-9]{40,64}$'),
    signature           text NULL,
    rule_count          integer NOT NULL CHECK (rule_count > 0),
    test_summary        jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(test_summary) = 'object'),
    created_by          text NOT NULL CHECK (length(created_by) BETWEEN 1 AND 500),
    created_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_policy_artifact_digest UNIQUE (digest),
    CONSTRAINT uq_policy_artifact_version UNIQUE (version)
);

CREATE TABLE gate_capabilities (
    gate                 text PRIMARY KEY CHECK (gate IN ('DESIGN','PR','PRE_DEPLOY','ADMISSION','RUNTIME')),
    enabled              boolean NOT NULL,
    enabled_from_mvp     integer NOT NULL CHECK (enabled_from_mvp BETWEEN 1 AND 5),
    updated_by           text NOT NULL,
    updated_at           timestamptz NOT NULL DEFAULT now()
);

INSERT INTO gate_capabilities(gate, enabled, enabled_from_mvp, updated_by) VALUES
    ('DESIGN', true, 1, 'schema-seed'),
    ('PR', true, 1, 'schema-seed'),
    ('PRE_DEPLOY', true, 2, 'schema-seed'),
    ('ADMISSION', false, 5, 'schema-seed'),
    ('RUNTIME', false, 5, 'schema-seed');

CREATE TABLE policy_activations (
    policy_activation_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_artifact_id   uuid NOT NULL REFERENCES policy_artifacts(policy_artifact_id) ON DELETE RESTRICT,
    scope_id             uuid NOT NULL REFERENCES estate_scopes(scope_id) ON DELETE RESTRICT,
    gate                 text NOT NULL CHECK (gate IN ('DESIGN','PR','PRE_DEPLOY','ADMISSION','RUNTIME')),
    selector_kind        text NOT NULL CHECK (selector_kind IN ('ALL','SERVICE_KEYS','LABELS')),
    selector_priority    integer NOT NULL CHECK (selector_priority BETWEEN 0 AND 10000),
    selector             jsonb NOT NULL CHECK (jsonb_typeof(selector) = 'object'),
    selector_hash        text NOT NULL CHECK (selector_hash ~ '^sha256:[a-f0-9]{64}$'),
    enforcement_mode     text NOT NULL CHECK (enforcement_mode IN ('OBSERVE','WARN','BLOCK')),
    reason               text NOT NULL CHECK (length(reason) BETWEEN 3 AND 2000),
    activated_by         text NOT NULL CHECK (length(activated_by) BETWEEN 1 AND 500),
    activated_at         timestamptz NOT NULL DEFAULT now(),
    ended_at             timestamptz NULL,
    superseded_by_id     uuid NULL REFERENCES policy_activations(policy_activation_id) ON DELETE RESTRICT,
    CONSTRAINT ck_activation_interval CHECK (ended_at IS NULL OR ended_at > activated_at),
    CONSTRAINT ck_activation_selector_projection CHECK (
        selector ->> 'kind' = selector_kind AND
        (selector ->> 'priority')::integer = selector_priority
    )
);

CREATE UNIQUE INDEX uq_active_policy_for_scope_gate_selector
    ON policy_activations(scope_id, gate, selector_hash)
    WHERE ended_at IS NULL;

CREATE INDEX ix_policy_activations_resolution
    ON policy_activations(scope_id, gate, selector_priority DESC, activated_at DESC);

-- Policy resolution audit supports stale-policy race analysis.
CREATE TABLE policy_resolution_events (
    policy_resolution_event_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_activation_id       uuid NOT NULL REFERENCES policy_activations(policy_activation_id) ON DELETE RESTRICT,
    service_id                 uuid NOT NULL REFERENCES services(service_id) ON DELETE RESTRICT,
    scope_id                   uuid NOT NULL REFERENCES estate_scopes(scope_id) ON DELETE RESTRICT,
    gate                       text NOT NULL CHECK (gate IN ('DESIGN','PR','PRE_DEPLOY','ADMISSION','RUNTIME')),
    caller_subject             text NOT NULL CHECK (length(caller_subject) BETWEEN 1 AND 500),
    cached_digest              text NULL CHECK (cached_digest IS NULL OR cached_digest ~ '^sha256:[a-f0-9]{64}$'),
    cache_status               text NOT NULL CHECK (cache_status IN ('HIT','MISS','NOT_PROVIDED')),
    correlation_id            uuid NOT NULL,
    resolved_at               timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_policy_resolution_events_service_time
    ON policy_resolution_events(service_id, resolved_at DESC);

-- CI validation evidence and centrally managed CI findings.
CREATE TABLE validation_runs (
    validation_run_id     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    client_run_id         uuid NOT NULL,
    idempotency_key       text NOT NULL CHECK (length(idempotency_key) BETWEEN 16 AND 128),
    request_hash          text NOT NULL CHECK (request_hash ~ '^sha256:[a-f0-9]{64}$'),
    service_id            uuid NOT NULL REFERENCES services(service_id) ON DELETE RESTRICT,
    service_version_id    uuid NULL REFERENCES service_versions(service_version_id) ON DELETE RESTRICT,
    scope_id              uuid NOT NULL REFERENCES estate_scopes(scope_id) ON DELETE RESTRICT,
    repository_provider   text NOT NULL CHECK (repository_provider IN ('GITHUB','GITLAB','AZURE_DEVOPS','BITBUCKET','OTHER')),
    repository_org        text NOT NULL CHECK (length(repository_org) BETWEEN 1 AND 200),
    repository_name       text NOT NULL CHECK (length(repository_name) BETWEEN 1 AND 200),
    source_commit         text NOT NULL CHECK (source_commit ~ '^[a-fA-F0-9]{40,64}$'),
    source_ref_type       text NOT NULL CHECK (source_ref_type IN ('BRANCH','TAG','PULL_REQUEST')),
    source_ref_name       text NOT NULL CHECK (length(source_ref_name) BETWEEN 1 AND 512),
    pull_request_number   bigint NULL CHECK (pull_request_number IS NULL OR pull_request_number > 0),
    gate                  text NOT NULL CHECK (gate IN ('DESIGN','PR','PRE_DEPLOY','ADMISSION','RUNTIME')),
    is_complete           boolean NOT NULL,
    gate_eligible         boolean GENERATED ALWAYS AS (is_complete AND ingestion_status = 'ACCEPTED') STORED,
    input_artifact_digest text NOT NULL CHECK (input_artifact_digest ~ '^sha256:[a-f0-9]{64}$'),
    input_media_type      text NOT NULL CHECK (length(input_media_type) BETWEEN 1 AND 200),
    input_artifact_object_id uuid NOT NULL REFERENCES artifact_objects(artifact_object_id) ON DELETE RESTRICT,
    architecture_digest  text NOT NULL CHECK (architecture_digest ~ '^sha256:[a-f0-9]{64}$'),
    policy_activation_id  uuid NOT NULL REFERENCES policy_activations(policy_activation_id) ON DELETE RESTRICT,
    policy_artifact_id    uuid NOT NULL REFERENCES policy_artifacts(policy_artifact_id) ON DELETE RESTRICT,
    evaluator_name        text NOT NULL CHECK (length(evaluator_name) BETWEEN 1 AND 100),
    evaluator_version     text NOT NULL CHECK (length(evaluator_version) BETWEEN 1 AND 100),
    evaluator_abi         text NOT NULL CHECK (length(evaluator_abi) BETWEEN 1 AND 100),
    verdict               text NOT NULL CHECK (verdict IN ('PASS','WARN','FAIL')),
    ingestion_status      text NOT NULL CHECK (ingestion_status IN ('ACCEPTED','STALE_POLICY','UNTRUSTED_EVALUATOR','REJECTED')),
    result_count          integer NOT NULL DEFAULT 0 CHECK (result_count >= 0),
    started_at            timestamptz NOT NULL,
    completed_at          timestamptz NOT NULL,
    submitted_by          text NOT NULL CHECK (length(submitted_by) BETWEEN 1 AND 500),
    submitted_at          timestamptz NOT NULL DEFAULT now(),
    accepted_at           timestamptz NULL,
    correlation_id       uuid NOT NULL,
    CONSTRAINT uq_validation_client_run UNIQUE (submitted_by, client_run_id),
    CONSTRAINT uq_validation_idempotency UNIQUE (submitted_by, idempotency_key),
    CONSTRAINT uq_validation_gate_reference UNIQUE (
        validation_run_id, service_id, service_version_id, scope_id,
        input_artifact_digest, architecture_digest, gate_eligible
    ),
    CONSTRAINT fk_validation_service_version FOREIGN KEY (
        service_version_id, service_id, input_artifact_digest
    ) REFERENCES service_versions (
        service_version_id, service_id, artifact_digest
    ) ON DELETE RESTRICT,
    CONSTRAINT ck_validation_time_order CHECK (completed_at >= started_at),
    CONSTRAINT ck_validation_acceptance CHECK (
        (ingestion_status = 'ACCEPTED' AND accepted_at IS NOT NULL) OR
        (ingestion_status <> 'ACCEPTED' AND accepted_at IS NULL)
    )
);

CREATE INDEX ix_validation_runs_service_time
    ON validation_runs(service_id, submitted_at DESC);
CREATE INDEX ix_validation_runs_artifact_policy
    ON validation_runs(service_id, scope_id, input_artifact_digest, policy_artifact_id, ingestion_status)
    WHERE is_complete;
CREATE INDEX ix_validation_runs_source
    ON validation_runs(repository_provider, repository_org, repository_name, source_commit);

CREATE TABLE validation_results (
    validation_result_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    validation_run_id    uuid NOT NULL REFERENCES validation_runs(validation_run_id) ON DELETE CASCADE,
    result_key           text NOT NULL CHECK (length(result_key) BETWEEN 1 AND 500),
    rule_id              text NOT NULL CHECK (length(rule_id) BETWEEN 1 AND 200),
    rule_version         text NULL CHECK (rule_version IS NULL OR length(rule_version) <= 100),
    severity             text NOT NULL CHECK (severity IN ('INFO','LOW','MEDIUM','HIGH','CRITICAL')),
    outcome              text NOT NULL CHECK (outcome IN ('PASS','WARN','FAIL','ERROR','SKIP')),
    message              text NOT NULL CHECK (length(message) BETWEEN 1 AND 8000),
    remediation          text NULL CHECK (remediation IS NULL OR length(remediation) <= 8000),
    documentation_url    text NULL,
    object_ref           jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(object_ref) = 'object'),
    source_location      jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(source_location) = 'object'),
    evidence             jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(evidence) = 'object'),
    created_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_validation_result_key UNIQUE (validation_run_id, result_key)
);

CREATE INDEX ix_validation_results_rule_outcome
    ON validation_results(rule_id, outcome, severity);

CREATE TABLE decision_context_snapshots (
    decision_context_snapshot_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    service_id           uuid NOT NULL REFERENCES services(service_id) ON DELETE RESTRICT,
    scope_id             uuid NOT NULL REFERENCES estate_scopes(scope_id) ON DELETE RESTRICT,
    schema_version       text NOT NULL CHECK (length(schema_version) BETWEEN 1 AND 100),
    digest               text NOT NULL CHECK (digest ~ '^sha256:[a-f0-9]{64}$'),
    artifact_digest      text NOT NULL CHECK (artifact_digest ~ '^sha256:[a-f0-9]{64}$'),
    artifact_object_id   uuid NOT NULL UNIQUE REFERENCES artifact_objects(artifact_object_id) ON DELETE RESTRICT,
    fact_watermarks      jsonb NOT NULL CHECK (jsonb_typeof(fact_watermarks) = 'object'),
    captured_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_decision_context_digest UNIQUE (service_id, scope_id, digest)
);

-- Authoritative estate-contextual decisions.
CREATE TABLE decisions (
    decision_id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key      text NOT NULL CHECK (length(idempotency_key) BETWEEN 16 AND 128),
    request_hash         text NOT NULL CHECK (request_hash ~ '^sha256:[a-f0-9]{64}$'),
    requested_by         text NOT NULL CHECK (length(requested_by) BETWEEN 1 AND 500),
    service_id           uuid NOT NULL REFERENCES services(service_id) ON DELETE RESTRICT,
    service_version_id   uuid NOT NULL REFERENCES service_versions(service_version_id) ON DELETE RESTRICT,
    scope_id             uuid NOT NULL REFERENCES estate_scopes(scope_id) ON DELETE RESTRICT,
    validation_run_id    uuid NOT NULL,
    validation_gate_eligible boolean NOT NULL DEFAULT true CHECK (validation_gate_eligible),
    policy_activation_id uuid NOT NULL REFERENCES policy_activations(policy_activation_id) ON DELETE RESTRICT,
    policy_artifact_id   uuid NOT NULL REFERENCES policy_artifacts(policy_artifact_id) ON DELETE RESTRICT,
    decision_context_snapshot_id uuid NOT NULL UNIQUE REFERENCES decision_context_snapshots(decision_context_snapshot_id) ON DELETE RESTRICT,
    intent               text NOT NULL CHECK (intent IN ('DEPLOY','PROMOTE')),
    artifact_digest      text NOT NULL CHECK (artifact_digest ~ '^sha256:[a-f0-9]{64}$'),
    architecture_digest  text NOT NULL CHECK (architecture_digest ~ '^sha256:[a-f0-9]{64}$'),
    evaluator_name       text NOT NULL CHECK (length(evaluator_name) BETWEEN 1 AND 100),
    evaluator_version    text NOT NULL CHECK (length(evaluator_version) BETWEEN 1 AND 100),
    evaluator_abi        text NOT NULL CHECK (length(evaluator_abi) BETWEEN 1 AND 100),
    verdict              text NOT NULL CHECK (verdict IN ('ALLOW','DENY','ADVISORY','UNDECIDABLE')),
    decided_at           timestamptz NOT NULL DEFAULT now(),
    expires_at           timestamptz NOT NULL,
    correlation_id       uuid NOT NULL,
    CONSTRAINT uq_decision_idempotency UNIQUE (requested_by, idempotency_key),
    CONSTRAINT uq_decision_deployment_reference UNIQUE (
        decision_id, service_id, service_version_id, scope_id, artifact_digest, verdict
    ),
    CONSTRAINT fk_decision_validation_evidence FOREIGN KEY (
        validation_run_id, service_id, service_version_id, scope_id,
        artifact_digest, architecture_digest, validation_gate_eligible
    ) REFERENCES validation_runs (
        validation_run_id, service_id, service_version_id, scope_id,
        input_artifact_digest, architecture_digest, gate_eligible
    ) ON DELETE RESTRICT,
    CONSTRAINT fk_decision_service_version FOREIGN KEY (
        service_version_id, service_id, artifact_digest
    ) REFERENCES service_versions (
        service_version_id, service_id, artifact_digest
    ) ON DELETE RESTRICT,
    CONSTRAINT ck_decision_expiry CHECK (expires_at > decided_at)
);

CREATE INDEX ix_decisions_service_scope_time
    ON decisions(service_id, scope_id, decided_at DESC);
CREATE INDEX ix_decisions_validation_run
    ON decisions(validation_run_id);

CREATE TABLE decision_results (
    decision_result_id   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    decision_id          uuid NOT NULL REFERENCES decisions(decision_id) ON DELETE CASCADE,
    result_key           text NOT NULL CHECK (length(result_key) BETWEEN 1 AND 500),
    rule_id              text NOT NULL CHECK (length(rule_id) BETWEEN 1 AND 200),
    rule_version         text NULL,
    severity             text NOT NULL CHECK (severity IN ('INFO','LOW','MEDIUM','HIGH','CRITICAL')),
    outcome              text NOT NULL CHECK (outcome IN ('PASS','WARN','FAIL','ERROR','SKIP')),
    message              text NOT NULL CHECK (length(message) BETWEEN 1 AND 8000),
    remediation          text NULL CHECK (remediation IS NULL OR length(remediation) <= 8000),
    evidence             jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(evidence) = 'object'),
    created_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_decision_result_key UNIQUE (decision_id, result_key)
);

-- Authenticated delivery evidence. This does not independently inspect the cluster.
CREATE TABLE deployments (
    deployment_id        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id             uuid NOT NULL,
    idempotency_key      text NOT NULL CHECK (length(idempotency_key) BETWEEN 16 AND 128),
    request_hash         text NOT NULL CHECK (request_hash ~ '^sha256:[a-f0-9]{64}$'),
    reported_by          text NOT NULL CHECK (length(reported_by) BETWEEN 1 AND 500),
    decision_id          uuid NOT NULL,
    decision_verdict     text NOT NULL DEFAULT 'ALLOW' CHECK (decision_verdict = 'ALLOW'),
    service_id           uuid NOT NULL REFERENCES services(service_id) ON DELETE RESTRICT,
    service_version_id   uuid NOT NULL REFERENCES service_versions(service_version_id) ON DELETE RESTRICT,
    scope_id             uuid NOT NULL REFERENCES estate_scopes(scope_id) ON DELETE RESTRICT,
    artifact_digest      text NOT NULL CHECK (artifact_digest ~ '^sha256:[a-f0-9]{64}$'),
    outcome              text NOT NULL CHECK (outcome IN ('SUCCEEDED','FAILED','ROLLED_BACK')),
    external_deployment_id text NULL CHECK (external_deployment_id IS NULL OR length(external_deployment_id) <= 500),
    deployed_at          timestamptz NOT NULL,
    received_at          timestamptz NOT NULL DEFAULT now(),
    correlation_id       uuid NOT NULL,
    CONSTRAINT uq_deployment_event_id UNIQUE (event_id),
    CONSTRAINT uq_deployment_idempotency UNIQUE (reported_by, idempotency_key),
    CONSTRAINT fk_deployment_allowed_decision FOREIGN KEY (
        decision_id, service_id, service_version_id, scope_id, artifact_digest, decision_verdict
    ) REFERENCES decisions (
        decision_id, service_id, service_version_id, scope_id, artifact_digest, verdict
    ) ON DELETE RESTRICT
);

CREATE INDEX ix_deployments_service_scope_time
    ON deployments(service_id, scope_id, deployed_at DESC);

-- One current finding plus append-only lifecycle events.
CREATE TABLE findings (
    finding_id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    finding_key_version  integer NOT NULL DEFAULT 1 CHECK (finding_key_version = 1),
    finding_key          text NOT NULL CHECK (length(finding_key) BETWEEN 1 AND 1000),
    affected_object_key  text NULL CHECK (affected_object_key IS NULL OR length(affected_object_key) BETWEEN 1 AND 1000),
    service_id           uuid NOT NULL REFERENCES services(service_id) ON DELETE RESTRICT,
    scope_id             uuid NULL REFERENCES estate_scopes(scope_id) ON DELETE RESTRICT,
    source_stage         text NOT NULL CHECK (source_stage IN ('CI','PRE_DEPLOY','DEPLOYMENT_EVIDENCE','POLICY_IMPACT','RUNTIME')),
    rule_id              text NOT NULL CHECK (length(rule_id) BETWEEN 1 AND 200),
    severity             text NOT NULL CHECK (severity IN ('INFO','LOW','MEDIUM','HIGH','CRITICAL')),
    status               text NOT NULL CHECK (status IN ('OPEN','ACKNOWLEDGED','WAIVED','RESOLVED')),
    title                text NOT NULL CHECK (length(title) BETWEEN 1 AND 500),
    detail               text NULL CHECK (detail IS NULL OR length(detail) <= 16000),
    assigned_to          text NULL CHECK (assigned_to IS NULL OR length(assigned_to) <= 300),
    current_validation_result_id uuid NULL REFERENCES validation_results(validation_result_id) ON DELETE SET NULL,
    current_decision_result_id   uuid NULL REFERENCES decision_results(decision_result_id) ON DELETE SET NULL,
    first_seen_at        timestamptz NOT NULL DEFAULT now(),
    last_seen_at         timestamptz NOT NULL DEFAULT now(),
    resolved_at          timestamptz NULL,
    occurrence_count     bigint NOT NULL DEFAULT 1 CHECK (occurrence_count > 0),
    version              integer NOT NULL DEFAULT 1 CHECK (version > 0),
    CONSTRAINT uq_finding_key UNIQUE (finding_key),
    CONSTRAINT ck_finding_key_digest CHECK (finding_key ~ '^sha256:[a-f0-9]{64}$'),
    CONSTRAINT uq_finding_identity UNIQUE NULLS NOT DISTINCT (
        service_id, scope_id, source_stage, rule_id, affected_object_key
    ),
    CONSTRAINT ck_finding_resolution CHECK (
        (status = 'RESOLVED' AND resolved_at IS NOT NULL) OR
        (status <> 'RESOLVED' AND resolved_at IS NULL)
    )
);

CREATE INDEX ix_findings_query
    ON findings(status, severity, source_stage, last_seen_at DESC);
CREATE INDEX ix_findings_service
    ON findings(service_id, status, last_seen_at DESC);

CREATE TABLE finding_events (
    finding_event_id     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    finding_id           uuid NOT NULL REFERENCES findings(finding_id) ON DELETE CASCADE,
    event_type           text NOT NULL CHECK (event_type IN (
                            'OPENED','OBSERVED','ACKNOWLEDGED','ASSIGNED','WAIVED',
                            'WAIVER_EXPIRED','RESOLVED','REOPENED','COMMENTED'
                        )),
    from_status          text NULL CHECK (from_status IS NULL OR from_status IN ('OPEN','ACKNOWLEDGED','WAIVED','RESOLVED')),
    to_status            text NOT NULL CHECK (to_status IN ('OPEN','ACKNOWLEDGED','WAIVED','RESOLVED')),
    actor                text NOT NULL CHECK (length(actor) BETWEEN 1 AND 500),
    comment              text NULL CHECK (comment IS NULL OR length(comment) <= 8000),
    evidence             jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(evidence) = 'object'),
    occurred_at          timestamptz NOT NULL DEFAULT now(),
    correlation_id       uuid NOT NULL
);

CREATE INDEX ix_finding_events_finding_time
    ON finding_events(finding_id, occurred_at);

CREATE TABLE waiver_requests (
    waiver_request_id    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key      text NOT NULL CHECK (length(idempotency_key) BETWEEN 16 AND 128),
    request_hash         text NOT NULL CHECK (request_hash ~ '^sha256:[a-f0-9]{64}$'),
    finding_id           uuid NOT NULL REFERENCES findings(finding_id) ON DELETE RESTRICT,
    requested_by         text NOT NULL CHECK (length(requested_by) BETWEEN 1 AND 500),
    reason               text NOT NULL CHECK (length(reason) BETWEEN 10 AND 8000),
    compensating_controls text NULL CHECK (compensating_controls IS NULL OR length(compensating_controls) <= 8000),
    requested_until      timestamptz NOT NULL,
    status               text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED','CANCELLED')),
    requested_at         timestamptz NOT NULL DEFAULT now(),
    decided_by           text NULL CHECK (decided_by IS NULL OR length(decided_by) <= 500),
    decision_comment     text NULL CHECK (decision_comment IS NULL OR length(decision_comment) <= 8000),
    decided_at           timestamptz NULL,
    CONSTRAINT uq_waiver_request_idempotency UNIQUE (requested_by, idempotency_key),
    CONSTRAINT ck_waiver_request_future CHECK (requested_until > requested_at),
    CONSTRAINT ck_waiver_request_decision CHECK (
        (status = 'PENDING' AND decided_by IS NULL AND decided_at IS NULL) OR
        (status = 'CANCELLED' AND decided_by = requested_by AND decision_comment IS NOT NULL AND decided_at IS NOT NULL) OR
        (status IN ('APPROVED','REJECTED') AND decided_by IS NOT NULL AND decision_comment IS NOT NULL AND decided_at IS NOT NULL)
    ),
    CONSTRAINT ck_waiver_approval_separation CHECK (
        status <> 'APPROVED' OR decided_by IS DISTINCT FROM requested_by
    )
);

CREATE UNIQUE INDEX uq_pending_waiver_request_per_finding
    ON waiver_requests(finding_id)
    WHERE status = 'PENDING';

CREATE TABLE waivers (
    waiver_id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    waiver_request_id    uuid NOT NULL UNIQUE REFERENCES waiver_requests(waiver_request_id) ON DELETE RESTRICT,
    finding_id           uuid NOT NULL REFERENCES findings(finding_id) ON DELETE RESTRICT,
    rule_id              text NOT NULL CHECK (length(rule_id) BETWEEN 1 AND 200),
    service_id           uuid NOT NULL REFERENCES services(service_id) ON DELETE RESTRICT,
    scope_id             uuid NULL REFERENCES estate_scopes(scope_id) ON DELETE RESTRICT,
    artifact_digest      text NULL CHECK (artifact_digest IS NULL OR artifact_digest ~ '^sha256:[a-f0-9]{64}$'),
    approved_by          text NOT NULL CHECK (length(approved_by) BETWEEN 1 AND 500),
    approved_at          timestamptz NOT NULL DEFAULT now(),
    expires_at           timestamptz NOT NULL,
    revoked_by           text NULL CHECK (revoked_by IS NULL OR length(revoked_by) <= 500),
    revoked_at           timestamptz NULL,
    revocation_reason    text NULL CHECK (revocation_reason IS NULL OR length(revocation_reason) <= 4000),
    CONSTRAINT ck_waiver_expiry CHECK (expires_at > approved_at),
    CONSTRAINT ck_waiver_revocation CHECK (
        (revoked_at IS NULL AND revoked_by IS NULL) OR
        (revoked_at IS NOT NULL AND revoked_by IS NOT NULL)
    ),
    CONSTRAINT uq_waiver_request_pair UNIQUE (waiver_id, waiver_request_id)
);

CREATE INDEX ix_active_waiver_match
    ON waivers(service_id, scope_id, rule_id, expires_at)
    WHERE revoked_at IS NULL;

CREATE TABLE waiver_events (
    waiver_event_id       uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    waiver_request_id     uuid NOT NULL REFERENCES waiver_requests(waiver_request_id) ON DELETE RESTRICT,
    waiver_id             uuid NULL REFERENCES waivers(waiver_id) ON DELETE RESTRICT,
    event_type            text NOT NULL CHECK (event_type IN (
                              'REQUESTED','APPROVED','REJECTED','CANCELLED',
                              'REVOKED','EXPIRED'
                          )),
    actor                 text NOT NULL CHECK (length(actor) BETWEEN 1 AND 500),
    comment               text NULL CHECK (comment IS NULL OR length(comment) <= 8000),
    occurred_at           timestamptz NOT NULL DEFAULT now(),
    correlation_id        uuid NOT NULL,
    CONSTRAINT fk_waiver_event_pair FOREIGN KEY (waiver_id, waiver_request_id)
        REFERENCES waivers(waiver_id, waiver_request_id) ON DELETE RESTRICT,
    CONSTRAINT ck_waiver_event_target CHECK (
        (event_type IN ('APPROVED','REVOKED','EXPIRED') AND waiver_id IS NOT NULL) OR
        (event_type IN ('REQUESTED','REJECTED','CANCELLED') AND waiver_id IS NULL)
    )
);

CREATE INDEX ix_waiver_events_request_time
    ON waiver_events(waiver_request_id, occurred_at);

CREATE OR REPLACE FUNCTION enforce_waiver_separation_of_duty()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    requester text;
    requested_limit timestamptz;
    request_status text;
    request_decider text;
    request_finding_id uuid;
    request_rule_id text;
    request_service_id uuid;
    request_scope_id uuid;
BEGIN
    SELECT wr.requested_by, wr.requested_until, wr.status, wr.decided_by,
           f.finding_id, f.rule_id, f.service_id, f.scope_id
      INTO requester, requested_limit, request_status, request_decider,
           request_finding_id, request_rule_id, request_service_id, request_scope_id
      FROM waiver_requests wr
      JOIN findings f ON f.finding_id = wr.finding_id
     WHERE wr.waiver_request_id = NEW.waiver_request_id
     FOR UPDATE OF wr, f;

    IF requester IS NULL THEN
        RAISE EXCEPTION 'waiver request % not found', NEW.waiver_request_id;
    END IF;
    IF requester = NEW.approved_by THEN
        RAISE EXCEPTION 'waiver requester cannot approve the same waiver';
    END IF;
    IF request_status <> 'APPROVED' OR request_decider IS DISTINCT FROM NEW.approved_by THEN
        RAISE EXCEPTION 'waiver request must be approved by the inserting approver';
    END IF;
    IF NEW.finding_id IS DISTINCT FROM request_finding_id OR
       NEW.rule_id IS DISTINCT FROM request_rule_id OR
       NEW.service_id IS DISTINCT FROM request_service_id OR
       NEW.scope_id IS DISTINCT FROM request_scope_id THEN
        RAISE EXCEPTION 'waiver identity must match its request and finding';
    END IF;
    IF NEW.expires_at > requested_limit THEN
        RAISE EXCEPTION 'approved waiver cannot exceed requested_until';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_waiver_separation_of_duty
BEFORE INSERT ON waivers
FOR EACH ROW EXECUTE FUNCTION enforce_waiver_separation_of_duty();

CREATE OR REPLACE FUNCTION enforce_waiver_request_lifecycle()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'waiver request history cannot be deleted';
    END IF;
    IF OLD.status <> 'PENDING' OR NEW.status NOT IN ('APPROVED','REJECTED','CANCELLED') OR
       NEW.waiver_request_id IS DISTINCT FROM OLD.waiver_request_id OR
       NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key OR
       NEW.request_hash IS DISTINCT FROM OLD.request_hash OR
       NEW.finding_id IS DISTINCT FROM OLD.finding_id OR
       NEW.requested_by IS DISTINCT FROM OLD.requested_by OR
       NEW.reason IS DISTINCT FROM OLD.reason OR
       NEW.compensating_controls IS DISTINCT FROM OLD.compensating_controls OR
       NEW.requested_until IS DISTINCT FROM OLD.requested_until OR
       NEW.requested_at IS DISTINCT FROM OLD.requested_at THEN
        RAISE EXCEPTION 'waiver request identity is immutable and permits one terminal transition';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_waiver_request_lifecycle
BEFORE UPDATE OR DELETE ON waiver_requests
FOR EACH ROW EXECUTE FUNCTION enforce_waiver_request_lifecycle();

CREATE OR REPLACE FUNCTION enforce_waiver_lifecycle()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'waiver history cannot be deleted';
    END IF;
    IF OLD.revoked_at IS NOT NULL OR
       NEW.waiver_id IS DISTINCT FROM OLD.waiver_id OR
       NEW.waiver_request_id IS DISTINCT FROM OLD.waiver_request_id OR
       NEW.finding_id IS DISTINCT FROM OLD.finding_id OR NEW.rule_id IS DISTINCT FROM OLD.rule_id OR
       NEW.service_id IS DISTINCT FROM OLD.service_id OR NEW.scope_id IS DISTINCT FROM OLD.scope_id OR
       NEW.artifact_digest IS DISTINCT FROM OLD.artifact_digest OR
       NEW.approved_by IS DISTINCT FROM OLD.approved_by OR NEW.approved_at IS DISTINCT FROM OLD.approved_at OR
       NEW.expires_at IS DISTINCT FROM OLD.expires_at OR
       NEW.revoked_at IS NULL OR NEW.revoked_by IS NULL OR NEW.revocation_reason IS NULL THEN
        RAISE EXCEPTION 'waiver identity is immutable and permits only one revocation transition';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_waiver_lifecycle
BEFORE UPDATE OR DELETE ON waivers
FOR EACH ROW EXECUTE FUNCTION enforce_waiver_lifecycle();

CREATE TABLE decision_context_waivers (
    decision_context_snapshot_id uuid NOT NULL REFERENCES decision_context_snapshots(decision_context_snapshot_id) ON DELETE RESTRICT,
    waiver_id            uuid NOT NULL REFERENCES waivers(waiver_id) ON DELETE RESTRICT,
    PRIMARY KEY (decision_context_snapshot_id, waiver_id)
);

CREATE OR REPLACE FUNCTION enforce_context_waiver_match()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    context_record record;
    waiver_record record;
    context_already_decided boolean;
BEGIN
    PERFORM pg_advisory_xact_lock(
        hashtextextended('decision-context|' || NEW.decision_context_snapshot_id::text, 0)
    );
    SELECT service_id, scope_id, artifact_digest, captured_at
      INTO context_record
      FROM decision_context_snapshots
     WHERE decision_context_snapshot_id = NEW.decision_context_snapshot_id
     FOR SHARE;
    SELECT service_id, scope_id, artifact_digest, expires_at, revoked_at
      INTO waiver_record
      FROM waivers
     WHERE waiver_id = NEW.waiver_id
     FOR SHARE;

    SELECT EXISTS (
        SELECT 1 FROM decisions
         WHERE decision_context_snapshot_id = NEW.decision_context_snapshot_id
    ) INTO context_already_decided;

    IF context_already_decided THEN
        RAISE EXCEPTION 'waivers cannot be added after a context snapshot has been decided';
    END IF;
    IF waiver_record.revoked_at IS NOT NULL OR waiver_record.expires_at <= context_record.captured_at OR
       waiver_record.service_id IS DISTINCT FROM context_record.service_id OR
       waiver_record.scope_id IS DISTINCT FROM context_record.scope_id OR
       (waiver_record.artifact_digest IS NOT NULL AND
        waiver_record.artifact_digest IS DISTINCT FROM context_record.artifact_digest) THEN
        RAISE EXCEPTION 'applied waiver is inactive, expired, or does not match decision context';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_context_waiver_match
BEFORE INSERT ON decision_context_waivers
FOR EACH ROW EXECUTE FUNCTION enforce_context_waiver_match();

-- Replica-safe scheduled work and reliable outward delivery.
CREATE TABLE scheduled_jobs (
    scheduled_job_id     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    job_type             text NOT NULL CHECK (job_type IN (
                            'POLICY_IMPACT','WAIVER_EXPIRY','EVIDENCE_CHECK',
                            'POST_DEPLOY_CHECK','RUNTIME_RECONCILIATION'
                        )),
    partition_key        text NOT NULL CHECK (length(partition_key) BETWEEN 1 AND 500),
    payload              jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(payload) = 'object'),
    due_at               timestamptz NOT NULL,
    status               text NOT NULL DEFAULT 'READY' CHECK (status IN ('READY','CLAIMED','SUCCEEDED','FAILED','DEAD')),
    attempt_count        integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    claimed_by           text NULL,
    claimed_until        timestamptz NULL,
    last_error           text NULL,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_scheduled_jobs_claim
    ON scheduled_jobs(status, due_at, partition_key)
    WHERE status IN ('READY','FAILED');

CREATE TABLE outbox_events (
    outbox_event_id      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type       text NOT NULL CHECK (length(aggregate_type) BETWEEN 1 AND 100),
    aggregate_id         uuid NOT NULL,
    event_type           text NOT NULL CHECK (length(event_type) BETWEEN 1 AND 200),
    event_version        integer NOT NULL DEFAULT 1 CHECK (event_version > 0),
    payload              jsonb NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    correlation_id       uuid NOT NULL,
    created_at           timestamptz NOT NULL DEFAULT now(),
    published_at         timestamptz NULL,
    attempt_count        integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at      timestamptz NOT NULL DEFAULT now(),
    last_error           text NULL
);

CREATE INDEX ix_outbox_unpublished
    ON outbox_events(next_attempt_at, created_at)
    WHERE published_at IS NULL;

CREATE TABLE idempotency_records (
    idempotency_record_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    subject               text NOT NULL CHECK (length(subject) BETWEEN 1 AND 500),
    operation             text NOT NULL CHECK (length(operation) BETWEEN 1 AND 200),
    idempotency_key       text NOT NULL CHECK (length(idempotency_key) BETWEEN 16 AND 128),
    request_hash          text NOT NULL CHECK (request_hash ~ '^sha256:[a-f0-9]{64}$'),
    response_status       integer NOT NULL CHECK (response_status BETWEEN 100 AND 599),
    response_body         jsonb NOT NULL CHECK (jsonb_typeof(response_body) = 'object'),
    resource_id           uuid NULL,
    created_at            timestamptz NOT NULL DEFAULT now(),
    expires_at            timestamptz NOT NULL,
    CONSTRAINT uq_idempotency_operation UNIQUE (subject, operation, idempotency_key),
    CONSTRAINT ck_idempotency_expiry CHECK (expires_at > created_at)
);

CREATE INDEX ix_idempotency_expiry ON idempotency_records(expires_at);

CREATE TABLE audit_events (
    audit_event_id       uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_subject        text NOT NULL CHECK (length(actor_subject) BETWEEN 1 AND 500),
    actor_type           text NOT NULL CHECK (actor_type IN ('USER','CI_WORKLOAD','SERVICE_WORKLOAD','SYSTEM')),
    operation            text NOT NULL CHECK (length(operation) BETWEEN 1 AND 200),
    resource_type        text NOT NULL CHECK (length(resource_type) BETWEEN 1 AND 100),
    resource_id          uuid NULL,
    outcome              text NOT NULL CHECK (outcome IN ('SUCCEEDED','DENIED','FAILED')),
    details              jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(details) = 'object'),
    correlation_id       uuid NOT NULL,
    occurred_at          timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_audit_events_time ON audit_events(occurred_at DESC);
CREATE INDEX ix_audit_events_resource ON audit_events(resource_type, resource_id, occurred_at DESC);

-- MVP 5 only: independent, read-only runtime observations.
CREATE TABLE observations (
    observation_id       uuid PRIMARY KEY,
    idempotency_key      text NOT NULL CHECK (length(idempotency_key) BETWEEN 16 AND 128),
    request_hash         text NOT NULL CHECK (request_hash ~ '^sha256:[a-f0-9]{64}$'),
    collector_subject    text NOT NULL CHECK (length(collector_subject) BETWEEN 1 AND 500),
    collector_id         text NOT NULL CHECK (length(collector_id) BETWEEN 1 AND 300),
    service_id           uuid NOT NULL REFERENCES services(service_id) ON DELETE RESTRICT,
    scope_id             uuid NOT NULL REFERENCES estate_scopes(scope_id) ON DELETE RESTRICT,
    deployment_id        uuid NULL REFERENCES deployments(deployment_id) ON DELETE SET NULL,
    content_digest       text NOT NULL CHECK (content_digest ~ '^sha256:[a-f0-9]{64}$'),
    changed              boolean NOT NULL,
    payload_artifact_object_id uuid NULL REFERENCES artifact_objects(artifact_object_id) ON DELETE RESTRICT,
    observed_at          timestamptz NOT NULL,
    received_at          timestamptz NOT NULL DEFAULT now(),
    correlation_id       uuid NOT NULL,
    CONSTRAINT uq_observation_idempotency UNIQUE (collector_subject, idempotency_key),
    CONSTRAINT ck_observation_payload CHECK (
        (changed AND payload_artifact_object_id IS NOT NULL) OR
        (NOT changed AND payload_artifact_object_id IS NULL)
    )
);

CREATE INDEX ix_observations_service_scope_time
    ON observations(service_id, scope_id, observed_at DESC);
CREATE INDEX ix_observations_collector_time
    ON observations(collector_id, received_at DESC);

CREATE TABLE observed_edges (
    observed_edge_id     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    observation_id       uuid NOT NULL REFERENCES observations(observation_id) ON DELETE CASCADE,
    source_service_id    uuid NOT NULL REFERENCES services(service_id) ON DELETE RESTRICT,
    target_service_key   text NOT NULL CHECK (length(target_service_key) BETWEEN 3 AND 200),
    protocol             text NULL CHECK (protocol IS NULL OR length(protocol) <= 50),
    evidence             jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(evidence) = 'object'),
    CONSTRAINT uq_observed_edge UNIQUE NULLS NOT DISTINCT (observation_id, target_service_key, protocol)
);

-- Cross-aggregate invariants that must hold even if a repository implementation is naive.
CREATE OR REPLACE FUNCTION policy_selector_matches(
    p_selector jsonb,
    p_service_key text,
    p_service_labels jsonb
)
RETURNS boolean
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT CASE p_selector ->> 'kind'
        WHEN 'ALL' THEN true
        WHEN 'SERVICE_KEYS' THEN EXISTS (
            SELECT 1
              FROM jsonb_array_elements_text(COALESCE(p_selector -> 'serviceKeys', '[]'::jsonb)) AS item(value)
             WHERE item.value = p_service_key
        )
        WHEN 'LABELS' THEN COALESCE(p_service_labels, '{}'::jsonb) @> COALESCE(p_selector -> 'matchLabels', '{}'::jsonb)
        ELSE false
    END;
$$;

CREATE OR REPLACE FUNCTION policy_selectors_overlap(p_left jsonb, p_right jsonb)
RETURNS boolean
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT CASE
        WHEN p_left ->> 'kind' IS DISTINCT FROM p_right ->> 'kind' THEN false
        WHEN p_left ->> 'kind' = 'ALL' THEN true
        WHEN p_left ->> 'kind' = 'SERVICE_KEYS' THEN EXISTS (
            SELECT 1
              FROM jsonb_array_elements_text(COALESCE(p_left -> 'serviceKeys', '[]'::jsonb)) AS l(value)
              JOIN jsonb_array_elements_text(COALESCE(p_right -> 'serviceKeys', '[]'::jsonb)) AS r(value)
                ON r.value = l.value
        )
        WHEN p_left ->> 'kind' = 'LABELS' THEN NOT EXISTS (
            SELECT 1
              FROM jsonb_each_text(COALESCE(p_left -> 'matchLabels', '{}'::jsonb)) AS l(label_key, label_value)
              JOIN jsonb_each_text(COALESCE(p_right -> 'matchLabels', '{}'::jsonb)) AS r(label_key, label_value)
                ON r.label_key = l.label_key
             WHERE r.label_value IS DISTINCT FROM l.label_value
        )
        ELSE false
    END;
$$;

CREATE OR REPLACE FUNCTION assert_verified_artifact_reference()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    object_id uuid;
    expected_kind text := TG_ARGV[1];
    digest_column text := TG_ARGV[2];
    expected_digest text;
    stored_kind text;
    stored_digest text;
    stored_status text;
BEGIN
    object_id := (to_jsonb(NEW) ->> TG_ARGV[0])::uuid;
    IF object_id IS NULL THEN
        RETURN NEW;
    END IF;
    expected_digest := to_jsonb(NEW) ->> digest_column;

    SELECT kind, digest, status
      INTO stored_kind, stored_digest, stored_status
      FROM artifact_objects
     WHERE artifact_object_id = object_id
     FOR SHARE;

    IF stored_status IS DISTINCT FROM 'VERIFIED' THEN
        RAISE EXCEPTION 'artifact object % is not verified', object_id;
    END IF;
    IF stored_kind IS DISTINCT FROM expected_kind OR stored_digest IS DISTINCT FROM expected_digest THEN
        RAISE EXCEPTION 'artifact object % kind/digest mismatch', object_id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_policy_artifact_verified_object
BEFORE INSERT ON policy_artifacts
FOR EACH ROW EXECUTE FUNCTION assert_verified_artifact_reference('artifact_object_id', 'POLICY', 'digest');

CREATE TRIGGER trg_validation_verified_object
BEFORE INSERT ON validation_runs
FOR EACH ROW EXECUTE FUNCTION assert_verified_artifact_reference('input_artifact_object_id', 'RENDERED_MANIFEST', 'input_artifact_digest');

CREATE TRIGGER trg_context_verified_object
BEFORE INSERT ON decision_context_snapshots
FOR EACH ROW EXECUTE FUNCTION assert_verified_artifact_reference('artifact_object_id', 'DECISION_CONTEXT', 'digest');

CREATE TRIGGER trg_observation_verified_object
BEFORE INSERT ON observations
FOR EACH ROW EXECUTE FUNCTION assert_verified_artifact_reference('payload_artifact_object_id', 'RUNTIME_OBSERVATION', 'content_digest');

CREATE OR REPLACE FUNCTION enforce_artifact_object_lifecycle()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status = 'VERIFIED' THEN
            RAISE EXCEPTION 'verified artifact metadata is immutable';
        END IF;
        RETURN OLD;
    END IF;
    IF OLD.status <> 'PENDING' THEN
        RAISE EXCEPTION 'completed artifact metadata is immutable';
    END IF;
    IF NEW.artifact_object_id IS DISTINCT FROM OLD.artifact_object_id OR
       NEW.kind IS DISTINCT FROM OLD.kind OR NEW.digest IS DISTINCT FROM OLD.digest OR
       NEW.media_type IS DISTINCT FROM OLD.media_type OR NEW.size_bytes IS DISTINCT FROM OLD.size_bytes OR
       NEW.storage_key IS DISTINCT FROM OLD.storage_key OR NEW.created_by IS DISTINCT FROM OLD.created_by OR
       NEW.created_at IS DISTINCT FROM OLD.created_at OR NEW.status NOT IN ('VERIFIED','REJECTED') THEN
        RAISE EXCEPTION 'artifact completion may only set verification outcome metadata';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_artifact_object_lifecycle
BEFORE UPDATE OR DELETE ON artifact_objects
FOR EACH ROW EXECUTE FUNCTION enforce_artifact_object_lifecycle();

CREATE OR REPLACE FUNCTION enforce_validation_run_invariants()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    activation_record record;
    service_record record;
    scope_active boolean;
    grant_exists boolean;
    capability_enabled boolean;
BEGIN
    IF NEW.ingestion_status <> 'ACCEPTED' THEN
        RETURN NEW;
    END IF;

    SELECT pa.scope_id, pa.gate, pa.policy_artifact_id, pa.selector, pa.ended_at,
           artifact.evaluator_abi
      INTO activation_record
      FROM policy_activations pa
      JOIN policy_artifacts artifact ON artifact.policy_artifact_id = pa.policy_artifact_id
     WHERE pa.policy_activation_id = NEW.policy_activation_id
     FOR SHARE OF pa, artifact;

    SELECT service_key, labels, is_active
      INTO service_record
      FROM services
     WHERE service_id = NEW.service_id
     FOR SHARE;
    SELECT is_active INTO scope_active FROM estate_scopes WHERE scope_id = NEW.scope_id FOR SHARE;
    SELECT enabled INTO capability_enabled FROM gate_capabilities WHERE gate = NEW.gate FOR SHARE;
    SELECT EXISTS (
        SELECT 1 FROM service_scope_grants
         WHERE service_id = NEW.service_id AND scope_id = NEW.scope_id
    ) INTO grant_exists;

    IF activation_record.ended_at IS NOT NULL OR
       activation_record.scope_id IS DISTINCT FROM NEW.scope_id OR
       activation_record.gate IS DISTINCT FROM NEW.gate OR
       activation_record.policy_artifact_id IS DISTINCT FROM NEW.policy_artifact_id OR
       activation_record.evaluator_abi IS DISTINCT FROM NEW.evaluator_abi THEN
        RAISE EXCEPTION 'accepted validation run has inactive or mismatched policy evidence';
    END IF;
    IF capability_enabled IS DISTINCT FROM true OR service_record.is_active IS DISTINCT FROM true OR
       scope_active IS DISTINCT FROM true OR NOT grant_exists THEN
        RAISE EXCEPTION 'accepted validation run targets a disabled gate, inactive service/scope, or ungranted scope';
    END IF;
    IF NOT policy_selector_matches(activation_record.selector, service_record.service_key, service_record.labels) THEN
        RAISE EXCEPTION 'accepted validation run policy selector does not match service';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_validation_run_invariants
BEFORE INSERT ON validation_runs
FOR EACH ROW EXECUTE FUNCTION enforce_validation_run_invariants();

CREATE OR REPLACE FUNCTION enforce_policy_activation_invariants()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    gate_enabled boolean;
    artifact_status text;
    selector_conflict boolean;
BEGIN
    SELECT enabled INTO gate_enabled
      FROM gate_capabilities
     WHERE gate = NEW.gate
     FOR SHARE;

    IF gate_enabled IS DISTINCT FROM true THEN
        RAISE EXCEPTION 'gate % is not enabled in this product stage', NEW.gate;
    END IF;

    SELECT ao.status INTO artifact_status
      FROM policy_artifacts pa
      JOIN artifact_objects ao ON ao.artifact_object_id = pa.artifact_object_id
     WHERE pa.policy_artifact_id = NEW.policy_artifact_id
     FOR SHARE OF pa, ao;

    IF artifact_status IS DISTINCT FROM 'VERIFIED' THEN
        RAISE EXCEPTION 'policy artifact is not backed by a verified object';
    END IF;

    -- Serialize potentially overlapping selectors so two concurrent activations cannot both pass the check.
    PERFORM pg_advisory_xact_lock(
        hashtextextended(
            NEW.scope_id::text || '|' || NEW.gate || '|' ||
            NEW.selector_priority::text || '|' || NEW.selector_kind,
            0
        )
    );
    SELECT EXISTS (
        SELECT 1
          FROM policy_activations existing
         WHERE existing.scope_id = NEW.scope_id
           AND existing.gate = NEW.gate
           AND existing.ended_at IS NULL
           AND existing.selector_priority = NEW.selector_priority
           AND existing.selector_kind = NEW.selector_kind
           AND policy_selectors_overlap(existing.selector, NEW.selector)
    ) INTO selector_conflict;
    IF selector_conflict THEN
        RAISE EXCEPTION 'active selector overlaps another selector at equal priority and specificity';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_policy_activation_invariants
BEFORE INSERT ON policy_activations
FOR EACH ROW EXECUTE FUNCTION enforce_policy_activation_invariants();

CREATE OR REPLACE FUNCTION enforce_policy_activation_lifecycle()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'policy activation history is append-only';
    END IF;
    IF OLD.ended_at IS NOT NULL OR NEW.ended_at IS NULL OR
       NEW.policy_activation_id IS DISTINCT FROM OLD.policy_activation_id OR
       NEW.policy_artifact_id IS DISTINCT FROM OLD.policy_artifact_id OR
       NEW.scope_id IS DISTINCT FROM OLD.scope_id OR NEW.gate IS DISTINCT FROM OLD.gate OR
       NEW.selector_kind IS DISTINCT FROM OLD.selector_kind OR
       NEW.selector_priority IS DISTINCT FROM OLD.selector_priority OR
       NEW.selector IS DISTINCT FROM OLD.selector OR NEW.selector_hash IS DISTINCT FROM OLD.selector_hash OR
       NEW.enforcement_mode IS DISTINCT FROM OLD.enforcement_mode OR NEW.reason IS DISTINCT FROM OLD.reason OR
       NEW.activated_by IS DISTINCT FROM OLD.activated_by OR NEW.activated_at IS DISTINCT FROM OLD.activated_at THEN
        RAISE EXCEPTION 'policy activation may only transition once from active to ended';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_policy_activation_lifecycle
BEFORE UPDATE OR DELETE ON policy_activations
FOR EACH ROW EXECUTE FUNCTION enforce_policy_activation_lifecycle();

CREATE OR REPLACE FUNCTION enforce_decision_invariants()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    validation_evidence record;
    decision_activation record;
    service_record record;
    scope_active boolean;
    grant_exists boolean;
    context_matches boolean;
    invalid_waiver_exists boolean;
BEGIN
    PERFORM pg_advisory_xact_lock(
        hashtextextended('decision-context|' || NEW.decision_context_snapshot_id::text, 0)
    );
    SELECT pa.ended_at, vr.policy_activation_id, vr.policy_artifact_id,
           vr.evaluator_name, vr.evaluator_version, vr.evaluator_abi
      INTO validation_evidence
      FROM validation_runs vr
      JOIN policy_activations pa ON pa.policy_activation_id = vr.policy_activation_id
     WHERE vr.validation_run_id = NEW.validation_run_id
       AND vr.ingestion_status = 'ACCEPTED'
       AND vr.is_complete
     FOR SHARE OF vr, pa;

    IF NOT FOUND OR validation_evidence.ended_at IS NOT NULL THEN
        RAISE EXCEPTION 'validation evidence is missing, incomplete, rejected, or stale';
    END IF;

    IF validation_evidence.policy_activation_id IS DISTINCT FROM NEW.policy_activation_id OR
       validation_evidence.policy_artifact_id IS DISTINCT FROM NEW.policy_artifact_id OR
       validation_evidence.evaluator_name IS DISTINCT FROM NEW.evaluator_name OR
       validation_evidence.evaluator_version IS DISTINCT FROM NEW.evaluator_version OR
       validation_evidence.evaluator_abi IS DISTINCT FROM NEW.evaluator_abi THEN
        RAISE EXCEPTION 'decision policy or evaluator does not match accepted validation evidence';
    END IF;

    SELECT pa.scope_id, pa.gate, pa.policy_artifact_id, pa.selector, pa.ended_at
      INTO decision_activation
      FROM policy_activations pa
     WHERE pa.policy_activation_id = NEW.policy_activation_id
     FOR SHARE;

    IF decision_activation.ended_at IS NOT NULL OR
       decision_activation.scope_id IS DISTINCT FROM NEW.scope_id OR
       decision_activation.gate IS DISTINCT FROM 'PRE_DEPLOY' OR
       decision_activation.policy_artifact_id IS DISTINCT FROM NEW.policy_artifact_id THEN
        RAISE EXCEPTION 'pre-deploy policy activation is inactive or mismatched';
    END IF;

    SELECT service_key, labels, is_active INTO service_record FROM services WHERE service_id = NEW.service_id FOR SHARE;
    SELECT is_active INTO scope_active FROM estate_scopes WHERE scope_id = NEW.scope_id FOR SHARE;
    SELECT EXISTS (
        SELECT 1 FROM service_scope_grants
         WHERE service_id = NEW.service_id AND scope_id = NEW.scope_id
    ) INTO grant_exists;

    SELECT EXISTS (
        SELECT 1 FROM decision_context_snapshots
         WHERE decision_context_snapshot_id = NEW.decision_context_snapshot_id
           AND service_id = NEW.service_id
           AND scope_id = NEW.scope_id
           AND artifact_digest = NEW.artifact_digest
    ) INTO context_matches;

    SELECT EXISTS (
        SELECT 1
          FROM decision_context_waivers dcw
          JOIN waivers w ON w.waiver_id = dcw.waiver_id
         WHERE dcw.decision_context_snapshot_id = NEW.decision_context_snapshot_id
           AND (
                w.revoked_at IS NOT NULL OR w.expires_at <= NEW.decided_at OR
                w.service_id IS DISTINCT FROM NEW.service_id OR
                w.scope_id IS DISTINCT FROM NEW.scope_id OR
                (w.artifact_digest IS NOT NULL AND w.artifact_digest IS DISTINCT FROM NEW.artifact_digest)
           )
    ) INTO invalid_waiver_exists;

    IF service_record.is_active IS DISTINCT FROM true OR scope_active IS DISTINCT FROM true OR NOT grant_exists THEN
        RAISE EXCEPTION 'service or scope is inactive, or service is not granted to scope';
    END IF;
    IF NOT policy_selector_matches(decision_activation.selector, service_record.service_key, service_record.labels) THEN
        RAISE EXCEPTION 'pre-deploy policy selector does not match service';
    END IF;
    IF NOT context_matches THEN
        RAISE EXCEPTION 'decision context snapshot does not match service, scope, and artifact';
    END IF;
    IF invalid_waiver_exists THEN
        RAISE EXCEPTION 'decision context contains an inactive, expired, or mismatched waiver';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_decision_invariants
BEFORE INSERT ON decisions
FOR EACH ROW EXECUTE FUNCTION enforce_decision_invariants();

CREATE OR REPLACE FUNCTION enforce_deployment_invariants()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    source_decision record;
BEGIN
    SELECT verdict, decided_at, expires_at
      INTO source_decision
      FROM decisions
     WHERE decision_id = NEW.decision_id
     FOR SHARE;

    IF source_decision.verdict IS DISTINCT FROM 'ALLOW' THEN
        RAISE EXCEPTION 'deployment requires an ALLOW decision';
    END IF;
    IF NEW.deployed_at < source_decision.decided_at OR NEW.deployed_at > source_decision.expires_at THEN
        RAISE EXCEPTION 'deployment timestamp falls outside decision validity';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_deployment_invariants
BEFORE INSERT ON deployments
FOR EACH ROW EXECUTE FUNCTION enforce_deployment_invariants();

-- Immutable evidence/history tables reject application updates and deletes.
CREATE OR REPLACE FUNCTION reject_immutable_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'table % is append-only/immutable', TG_TABLE_NAME;
END;
$$;

CREATE TRIGGER trg_policy_artifacts_immutable BEFORE UPDATE OR DELETE ON policy_artifacts
FOR EACH ROW EXECUTE FUNCTION reject_immutable_mutation();
CREATE TRIGGER trg_policy_resolution_events_immutable BEFORE UPDATE OR DELETE ON policy_resolution_events
FOR EACH ROW EXECUTE FUNCTION reject_immutable_mutation();
CREATE TRIGGER trg_validation_runs_immutable BEFORE UPDATE OR DELETE ON validation_runs
FOR EACH ROW EXECUTE FUNCTION reject_immutable_mutation();
CREATE TRIGGER trg_validation_results_immutable BEFORE UPDATE OR DELETE ON validation_results
FOR EACH ROW EXECUTE FUNCTION reject_immutable_mutation();
CREATE TRIGGER trg_decision_context_immutable BEFORE UPDATE OR DELETE ON decision_context_snapshots
FOR EACH ROW EXECUTE FUNCTION reject_immutable_mutation();
CREATE TRIGGER trg_decision_context_waivers_immutable BEFORE UPDATE OR DELETE ON decision_context_waivers
FOR EACH ROW EXECUTE FUNCTION reject_immutable_mutation();
CREATE TRIGGER trg_decisions_immutable BEFORE UPDATE OR DELETE ON decisions
FOR EACH ROW EXECUTE FUNCTION reject_immutable_mutation();
CREATE TRIGGER trg_decision_results_immutable BEFORE UPDATE OR DELETE ON decision_results
FOR EACH ROW EXECUTE FUNCTION reject_immutable_mutation();
CREATE TRIGGER trg_deployments_immutable BEFORE UPDATE OR DELETE ON deployments
FOR EACH ROW EXECUTE FUNCTION reject_immutable_mutation();
CREATE TRIGGER trg_finding_events_immutable BEFORE UPDATE OR DELETE ON finding_events
FOR EACH ROW EXECUTE FUNCTION reject_immutable_mutation();
CREATE TRIGGER trg_waiver_events_immutable BEFORE UPDATE OR DELETE ON waiver_events
FOR EACH ROW EXECUTE FUNCTION reject_immutable_mutation();
CREATE TRIGGER trg_audit_events_immutable BEFORE UPDATE OR DELETE ON audit_events
FOR EACH ROW EXECUTE FUNCTION reject_immutable_mutation();
CREATE TRIGGER trg_observations_immutable BEFORE UPDATE OR DELETE ON observations
FOR EACH ROW EXECUTE FUNCTION reject_immutable_mutation();
CREATE TRIGGER trg_observed_edges_immutable BEFORE UPDATE OR DELETE ON observed_edges
FOR EACH ROW EXECUTE FUNCTION reject_immutable_mutation();

-- Shared timestamp maintenance.
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_services_updated_at
BEFORE UPDATE ON services
FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_scheduled_jobs_updated_at
BEFORE UPDATE ON scheduled_jobs
FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMIT;
