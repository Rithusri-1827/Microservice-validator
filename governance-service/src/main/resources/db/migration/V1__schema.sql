-- DD Part IV (04 rev 5): full governance-store schema. Postgres 16; forward-only migrations.

CREATE TABLE rule_bundles(
  version         text PRIMARY KEY,
  git_commit      text,
  content_hash    text UNIQUE,
  grammar_version text NOT NULL,
  manifest        jsonb NOT NULL,
  published_by    text,
  published_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE active_bundle(
  stage   text PRIMARY KEY,           -- canonical: ci | intake | runtime
  version text NOT NULL REFERENCES rule_bundles(version)
);

CREATE TABLE policy_rules(
  bundle_version text REFERENCES rule_bundles(version),
  rule_id        text,
  family         text NOT NULL,
  severity       text NOT NULL,
  phases         text[] NOT NULL,
  environments   text[] NOT NULL,
  definition     jsonb NOT NULL,
  PRIMARY KEY(bundle_version, rule_id)
);

CREATE TABLE environments(
  name           text PRIMARY KEY,
  capacity       jsonb NOT NULL,      -- {cpu: millicores, memory: bytes} — REQ-004 right-hand side
  settle_delay_s int NOT NULL DEFAULT 120,
  order_index    int                  -- informational only; promotion order lives in LCY rule params
);

CREATE TABLE services(
  service_id text PRIMARY KEY,
  name       text,
  layer      text,
  team       text
);

CREATE TABLE service_versions(
  service_id   text REFERENCES services(service_id),
  version      text,
  image_digest text,
  declared_cdm jsonb,                 -- CI-approved baseline (rides the deployment event)
  first_seen   timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(service_id, version)
);

CREATE TABLE lifecycle_states(
  service_id         text,
  version            text,
  environment        text REFERENCES environments(name),
  state              text NOT NULL,   -- Deployed|Validated|ValidationFailed|Superseded|Decommissioned
  last_report_at     timestamptz,
  next_validation_at timestamptz,     -- persisted settle timer (restart-safe, D3-10 family)
  live_snapshot      jsonb,
  stale              boolean NOT NULL DEFAULT false,
  PRIMARY KEY(service_id, version, environment),
  FOREIGN KEY(service_id, version) REFERENCES service_versions(service_id, version)
);
CREATE INDEX ls_due ON lifecycle_states(next_validation_at) WHERE next_validation_at IS NOT NULL;
CREATE INDEX ls_env ON lifecycle_states(environment, state);

CREATE TABLE lifecycle_history(
  id          bigserial PRIMARY KEY,
  service_id  text NOT NULL,
  version     text NOT NULL,
  environment text NOT NULL,
  from_state  text,
  to_state    text NOT NULL,
  event_id    text,
  at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX lh_svc ON lifecycle_history(service_id, environment, at DESC);

CREATE TABLE topology_snapshots(
  id          bigserial PRIMARY KEY,
  environment text NOT NULL,
  origin      text NOT NULL CHECK (origin IN ('declared','captured')),
  graph       jsonb NOT NULL,
  at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ts_latest ON topology_snapshots(environment, origin, at DESC);

CREATE TABLE evaluations(
  event_id       text PRIMARY KEY,
  service_id     text,
  version        text,
  environment    text,
  phase          text NOT NULL,       -- intake | runtime
  bundle_version text,
  verdict        text NOT NULL,       -- PASS | FAIL | ERROR
  duration_ms    int,
  at             timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ev_service ON evaluations(service_id, environment, at DESC);

CREATE TABLE violations(
  id          bigserial PRIMARY KEY,
  rule_id     text NOT NULL,          -- bundle rule id or synthetic RECON-* / DRIFT-*
  service_id  text NOT NULL,
  version     text NOT NULL,
  environment text NOT NULL,
  status      text NOT NULL,          -- OPEN | ACKNOWLEDGED | WAIVED | RESOLVED
  first_seen  timestamptz NOT NULL DEFAULT now(),
  last_seen   timestamptz NOT NULL DEFAULT now(),
  occurrences int NOT NULL DEFAULT 1,
  detail      jsonb,                  -- incl. source: evaluation|baseline-audit|reconciliation
  waiver_id   bigint
);
CREATE UNIQUE INDEX v_open ON violations(rule_id, service_id, version, environment)
  WHERE status IN ('OPEN','ACKNOWLEDGED','WAIVED');
CREATE INDEX v_board ON violations(environment, status, last_seen DESC);

CREATE TABLE waivers(
  id          bigserial PRIMARY KEY,
  rule_id     text NOT NULL,
  service_id  text NOT NULL,
  version     text NOT NULL,
  environment text NOT NULL,
  approved_by text NOT NULL,
  reason      text,
  expires_at  timestamptz NOT NULL,
  status      text NOT NULL DEFAULT 'active'   -- active | expired | revoked
);
CREATE INDEX w_active ON waivers(expires_at) WHERE status = 'active';

CREATE TABLE publish_audit(
  id             bigserial PRIMARY KEY,
  bundle_version text,
  action         text NOT NULL,       -- publish | activate | rollback
  actor          text,
  at             timestamptz NOT NULL DEFAULT now()
);
