# API Contract Boundaries

This catalog defines which contract each caller implements. The three OpenAPI documents are independently consumable and are the normative HTTP contracts for their boundary.

| Boundary | Normative contract | Intended callers | Exposure and authentication |
|---|---|---|---|
| Edge | [openapi-edge.yaml](openapi-edge.yaml) | CLI, CI/CD workloads, protected policy pipeline, deploy hook, and optional post-pilot collector | Workload-facing ingress; workload OIDC and scoped machine authorization |
| UI | [openapi-ui.yaml](openapi-ui.yaml) | Rio browser application and user-driven integrations | User-facing ingress; user OIDC and role/scope authorization |
| Governance internal | [openapi-governance-internal.yaml](openapi-governance-internal.yaml) | Kubernetes probes and platform operations | Cluster-only operational listener; not routed through public ingress |

The Edge and UI contracts may be served by the same pilot Governance Plane deployment, but they are separate consumer contracts and can be routed, secured, rate-limited, generated, and versioned independently. Five read operations intentionally appear in both contracts because both machine and human callers require the same resource representation: validation run, estate-scope list/detail, service detail, and decision detail. Their method, operation, and reachable component definitions must remain identical. No mutation is shared between Edge and UI.

The pilot Governance Plane is a modular monolith. Calls between its policy, validation, decision, findings, waiver, estate, and evidence modules are in-process typed calls—not internal HTTP endpoints. The internal OpenAPI therefore contains only deployable-process operational probes. Introducing domain-level service-to-service APIs requires an ADR and an explicit post-pilot service-extraction decision.

The Governance Plane implements all three contracts in Java. The Edge API is consumed by Python CLI/CI tooling and authorized delivery systems; the UI API is consumed by the separate Python Rio process. Java owns central authorization, domain transitions, workers, and PostgreSQL access. Contract generation or conformance tests must validate Java server adapters and Python clients against these same files. The internal health contract remains a cluster-only Java listener.

## Edge contract operations

| Method and route | Purpose | Primary caller | Delivery boundary |
|---|---|---|---|
| `GET /v1/policy-resolutions` | Resolve active policy, selector, enforcement mode, artifact digest, and sync metadata | CLI / CI | MVP 2 |
| `GET /v1/policy-artifacts/{digest}/content` | Download immutable policy bytes by digest | CLI / CI | MVP 2 |
| `POST /v1/artifact-uploads` | Initiate a governed evidence or artifact upload | CI / policy pipeline | MVP 2 |
| `POST /v1/artifact-uploads/{artifactObjectId}/complete` | Finalize and verify an uploaded object | CI / policy pipeline | MVP 2 |
| `POST /v1/policy-artifacts` | Register a tested immutable policy artifact | Protected policy pipeline | MVP 2 |
| `POST /v1/policy-activations` | Activate or roll back policy for an authorized scope | Policy automation / approver workflow | MVP 2 |
| `POST /v1/validation-runs` | Publish validation results and rule findings | CI workload | MVP 2 |
| `GET /v1/validation-runs/{validationRunId}` | Read ingestion and finding linkage | CI / audit automation | MVP 2 |
| `GET`, `POST /v1/estate-scopes` | Discover or create an authorized target scope | CI / platform automation | MVP 2 |
| `GET`, `PATCH /v1/estate-scopes/{scopeId}` | Read or change scope lifecycle | CI / platform automation | MVP 2 |
| `GET`, `PUT /v1/services/{serviceKey}` | Read or register service identity and ownership | CI / service automation | MVP 2 |
| `PATCH /v1/services/{serviceKey}/lifecycle` | Change service lifecycle | Platform automation | MVP 2 |
| `PUT /v1/declarations/{serviceKey}` | Publish accepted architecture declaration | Protected-merge CI | MVP 2 |
| `POST /v1/decisions` | Request authoritative pre-deploy decision | CI/CD workload | MVP 2 |
| `GET /v1/decisions/{decisionId}` | Read decision and evidence metadata | CI/CD / audit automation | MVP 2 |
| `POST /v1/deployment-events` | Record authenticated deployment outcome | Deploy hook | MVP 2 |
| `POST /v1/observations` | Publish runtime observation | Optional collector | Post-pilot MVP 5 only |

## UI contract operations

| Method and route | Purpose | Primary caller | Delivery boundary |
|---|---|---|---|
| `GET /v1/validation-runs/{validationRunId}` | Inspect a validation run and linked findings | Rio UI | MVP 2 |
| `GET /v1/estate-scopes` | Browse authorized estate scopes | Rio UI | MVP 2 |
| `GET /v1/estate-scopes/{scopeId}` | Inspect a scope | Rio UI | MVP 2 |
| `GET /v1/services/{serviceKey}` | Inspect service registration | Rio UI | MVP 2 |
| `GET /v1/decisions/{decisionId}` | Inspect an authoritative decision | Rio UI | MVP 2 |
| `GET /v1/findings` | Search and filter findings | Rio UI | MVP 3 |
| `GET /v1/findings/{findingId}` | Inspect evidence and history | Rio UI | MVP 3 |
| `POST /v1/findings/{findingId}/transitions` | Apply a legal finding-state transition | Rio UI | MVP 3 |
| `POST /v1/findings/{findingId}/acknowledgements` | Acknowledge and assign a finding | Rio UI | MVP 3 |
| `POST /v1/waiver-requests` | Request a scoped, time-bound exception | Rio UI | MVP 3 |
| `GET /v1/waiver-requests/{waiverRequestId}` | Inspect waiver request and decision status | Rio UI | MVP 3 |
| `POST /v1/waiver-requests/{waiverRequestId}/decisions` | Approve or reject a waiver request | Rio UI | MVP 3 |
| `POST /v1/waiver-requests/{waiverRequestId}/cancellation` | Cancel a pending request | Rio UI | MVP 3 |
| `GET /v1/waivers` | Search active and historical waivers | Rio UI | MVP 3 |
| `POST /v1/waivers/{waiverId}/revocations` | Revoke an active waiver | Rio UI | MVP 3 |
| `GET /v1/services/{serviceKey}/posture` | Read service posture, evidence, and active exceptions | Rio UI | MVP 3 |

## Governance internal contract operations

| Method and route | Purpose | Caller | Exposure |
|---|---|---|---|
| `GET /health/liveness` | Confirm the process event loop is alive | Kubernetes liveness probe | Cluster only |
| `GET /health/readiness` | Confirm the replica can serve traffic and reach mandatory dependencies | Kubernetes readiness probe | Cluster only |

These probes deliberately do not use end-user or workload OIDC. Network policy and listener binding restrict access to the cluster operations path. They expose no domain data.

## Compatibility rules

- Every operation belongs to at least one declared consumer boundary.
- Shared Edge/UI reads are byte-for-byte contract-compatible at the path-operation and referenced-schema levels.
- Edge/UI mutations require `Idempotency-Key`; health probes do not.
- A schema is copied into a specification only when reachable from one of its operations.
- Internal operation identifiers cannot overlap Edge or UI operation identifiers.
- CI runs [the contract validator](../scripts/validate_contracts.py) to enforce the operation inventory, allowed overlap, references, boundary markers, policy-sync requirements, waiver invariants, and database checks.
