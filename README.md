# clj-oa3-vtn

[![Clojars Project](https://img.shields.io/clojars/v/energy.grid-coordination/clj-oa3-vtn.svg)](https://clojars.org/energy.grid-coordination/clj-oa3-vtn)
[![MIT License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![md-docs](https://img.shields.io/badge/md--docs-included-green)](https://github.com/dcj/codox-md)
[![build-provenance](https://img.shields.io/badge/build--provenance-included-blue)](https://github.com/dcj/build-provenance)

Production OpenADR 3.1.0 Virtual Top Node (VTN) server in Clojure.

## Overview

An OpenADR 3 VTN that serves as a **public price server** — publishing electricity pricing programs and events over HTTP and MQTT. Business logic clients (BL clients) push pricing data in; VEN clients read it and subscribe for notifications.

### Two-Port Architecture

The VTN exposes two HTTP ports with different access levels:

| Port | Role | Access |
|------|------|--------|
| **8081** (BL) | Business Logic | Full CRUD on programs, events, subscriptions |
| **8080** (VEN) | Virtual End Node | Read programs/events (configurable per resource) |

BL clients run co-located with the VTN and are trusted by network topology — no authentication in Phase 1. VEN clients are public-facing and by default can only read programs and events. Additional resource access (subscriptions, VENs, resources, reports) can be enabled per resource type via `:ven-routes` config.

Both ports share the same storage and MQTT notification system.

### What's Implemented (Phase 1)

- **Programs** — CRUD with duplicate name conflict detection (409)
- **Events** — CRUD with programID foreign key validation
- **Subscriptions** — CRUD with auto-assigned clientID
- **MQTT Notifications** — CREATE/UPDATE/DELETE published to topic hierarchy
- **Notifier Discovery** — `GET /notifiers` returns per-port notifier config (VEN: MQTT only, BL: MQTT + WEBHOOK)
- **MQTT Topic Discovery** — All 12 topic endpoints per OpenADR 3.1.0
- **Auth Stubs** — `GET /auth/server` returns discovery, `POST /auth/token` returns 501
- **Pagination** — skip/limit on all search endpoints (max 50)

### Not Yet Implemented

- Authentication (OAuth2 client credentials)
- VEN registration, Resources, Reports
- Webhook notifications
- PostgreSQL persistence (currently in-memory atoms)
- AWS cloud deployment

## Quick Start

### Prerequisites

- Java 21+
- [Clojure CLI](https://clojure.org/guides/install_clojure) 1.12+
- [Mosquitto](https://mosquitto.org/) MQTT broker (or `docker run -p 1883:1883 eclipse-mosquitto`)

### Start the VTN

```bash
# Start Mosquitto (if not already running)
docker run -d -p 1883:1883 eclipse-mosquitto

# Start the VTN via nREPL
clojure -M:nrepl
```

In the REPL:

```clojure
(require '[user :refer [start stop reset]])

;; Start the VTN (BL on 8081, VEN on 8080)
(start)

;; Restart with fresh state
(reset)

;; Stop
(stop)

;; Start with custom config
(start {:ven-port 9080 :bl-port 9081})
```

### Smoke Test

```bash
# Create a program (BL port)
curl -X POST http://localhost:8081/openadr3/3.1.0/programs \
  -H 'Content-Type: application/json' \
  -d '{"programName":"PG&E-TOU","programLongName":"PG&E Time of Use"}'

# Read programs (VEN port)
curl http://localhost:8080/openadr3/3.1.0/programs

# Look up a program by name (no scan, GSI-backed)
curl 'http://localhost:8080/openadr3/3.1.0/programs?programName=PG%26E-TOU'

# Create an event with pricing (intervalPeriod required for GSI indexing)
PROGRAM_ID=<id-from-above>
curl -X POST http://localhost:8081/openadr3/3.1.0/events \
  -H 'Content-Type: application/json' \
  -d "{\"programID\":\"$PROGRAM_ID\",\"intervalPeriod\":{\"start\":\"2025-01-15T00:00:00Z\",\"duration\":\"PT1H\"},\"intervals\":[{\"id\":0,\"payloads\":[{\"type\":\"PRICE\",\"values\":[0.25]}]}]}"

# VEN port rejects writes
curl -X POST http://localhost:8080/openadr3/3.1.0/programs \
  -H 'Content-Type: application/json' \
  -d '{"programName":"rejected"}'
# → 404

# Discover MQTT topics
curl http://localhost:8080/openadr3/3.1.0/notifiers
curl http://localhost:8080/openadr3/3.1.0/notifiers/mqtt/topics/programs
```

## Configuration

By default, the VTN loads `resources/config.edn` from the classpath. To use an external config file, set either:

- **System property**: `-Dopenadr3.config=/path/to/config.edn`
- **Environment variable**: `CLJ_OA3_VTN_CONFIG=/path/to/config.edn`

The system property takes precedence. If the specified file doesn't exist, the VTN falls back to the classpath resource. External config values are merged with built-in defaults, so you only need to specify keys you want to override.

Default config in `resources/config.edn`:

```edn
{:ven-port 8080
 :bl-port 8081
 :context-path "/openadr3/3.1.0"
 :mqtt-broker-url "mqtt://localhost:1883"
 :mqtt-retained false
 :storage-backend :memory  ;; :memory (default) or :dynamodb

 ;; For :memory backend — optional file persistence via duratom:
 ;; :storage-file-path "/tmp/vtn-store.edn"

 ;; For :dynamodb backend:
 ;; :dynamodb-table "openadr3"
 ;; :dynamodb-region "us-west-2"
 ;; :dynamodb-ensure-table true  ;; auto-create table (dev only)

 ;; VEN port resource access control.
 ;; :read-only = GET only, :full = CRUD, false = disabled (404).
 :ven-routes {:programs      :read-only
              :events        :read-only
              :subscriptions false
              :vens          false
              :resources     false
              :reports       false}

 ;; Per-port notifier configuration.
 ;; Controls what GET /notifiers returns on each port.
 :ven-notifiers {:MQTT {:authentication {:method "ANONYMOUS"}}}
 :bl-notifiers  {:MQTT {:authentication {:method "ANONYMOUS"}}
                 :WEBHOOK true}

 ;; nREPL server for production inspection (disabled by default).
 ;; Access via SSH tunnel or ECS execute-command — never expose publicly.
 ;; :nrepl {:enabled true, :port 7888, :bind "localhost"}
 }
```

The `:ven-routes` map controls which resources the VEN port exposes. Disabled resources return 404/405 and their MQTT topic discovery endpoints are also suppressed. See [doc/mqtt-broker-security.md](doc/mqtt-broker-security.md) for MQTT broker ACL configuration.

The `:ven-notifiers` and `:bl-notifiers` maps control what `GET /notifiers` returns on each port. The VEN port advertises MQTT only (no webhook support for public price consumers). The BL port advertises both. MQTT broker URL and serialization are filled in automatically from `:mqtt-broker-url`. Supports `mqtt://`, `mqtts://`, `ws://`, and `wss://` URI schemes.

Storage backends:
- **`:memory`** (default) — in-memory atom. Set `:storage-file-path` for file persistence via [duratom](https://github.com/jimpil/duratom). Fine for dev and moderate data.
- **`:dynamodb`** — AWS DynamoDB via [Cognitect aws-api](https://github.com/cognitect-labs/aws-api). Single-table design with GSIs for programName and programID lookups. Set `:dynamodb-table`, `:dynamodb-region`, and optionally `:dynamodb-ensure-table true` for auto-creation (dev/local DynamoDB).

Override any key by passing a map to `(start {...})` in the REPL.

## REPL Development

The VTN uses [Stuart Sierra's Component](https://github.com/stuartsierra/component) for lifecycle management.

```clojure
(start)   ;; creates and starts all components, prints startup banner
(stop)    ;; stops all components, releases ports
(reset)   ;; stop + start with fresh state (new atom, new connections)
(status)  ;; print system health: servers, MQTT, storage counts
```

`(start)` prints a banner showing the bound ports and MQTT broker. If a port is already in use, it throws immediately with a clear error message rather than failing silently.

The Component system map:

```
:config            → loads config.edn
:raw-storage       → AtomStorage or DynamoStorage (depends on :config)
:validated-storage → ValidatingStorage — Malli schema checks on write (wraps :raw-storage)
:mqtt-publisher    → Paho MQTT client (depends on :config)
:notifier          → routes C/U/D to MQTT topics (depends on :mqtt-publisher)
:storage           → NotifyingStorage — auto-publishes MQTT on C/U/D (wraps :validated-storage; opt-out per-write via ^:suppress-notify metadata)
:http-server-bl    → Jetty on BL port, full CRUD routes (depends on :storage, :config)
:http-server-ven   → Jetty on VEN port, read+subscribe routes (depends on :storage, :config)
:nrepl             → nREPL server (optional, only when :nrepl :enabled is true)
```

### nREPL

**Development:** The nREPL server auto-assigns a free port and writes it to `.nrepl-port`. CIDER (`cider-connect`) and clojure-mcp both discover this file automatically.

**Production:** Enable the embedded nREPL server for live inspection of a running VTN:

```edn
:nrepl {:enabled true, :port 7888, :bind "localhost"}
```

Disabled by default — the component is omitted from the system map entirely when `:enabled` is false or absent. Bind to `localhost` only and access via SSH tunnel or `aws ecs execute-command`. Never expose the nREPL port publicly.

### Logging

The VTN uses [mulog](https://github.com/BrunoBonacci/mulog) for structured event logging. Events are Clojure maps with namespace-qualified keywords — queryable, filterable, and dashboard-friendly.

In dev, `dev/user.clj` auto-starts a `:console` publisher so you see events in the REPL:

```
{:mulog/event-name :openadr3.vtn.middleware/http-request,
 :method :get, :uri /programs, :status 200, :duration-ms 3, :remote-addr 127.0.0.1}
{:mulog/event-name :openadr3.vtn.mqtt/published,
 :topic OpenADR/3.1.0/programs/create, :retained false}
```

For production, start a JSON publisher in `-main` or add a file/Elasticsearch/CloudWatch publisher:

```clojure
(mu/start-publisher! {:type :console :pretty? false})  ;; JSON lines
```

Key events: `::http-request` (method, uri, status, duration-ms, remote-addr), `::mqtt/published` (topic), `::mqtt/connected` / `::mqtt/disconnected`, `::http/started` / `::http/stopped` (role, port).

## Testing

### Unit Tests

```bash
clojure -M:test
# 65 tests, 292 assertions
```

### Integration Tests

Uses the [clj-oa3-test](../clj-oa3-test) suite. Configure `test-config.edn` in that repo:

```edn
{:bl-url  "http://localhost:8081/openadr3/3.1.0"
 :ven-url "http://localhost:8080/openadr3/3.1.0"
 :expected-notifiers #{:MQTT}
 :tokens {:ven1 "dmVuX2NsaWVudDo5OTk="
          :ven2 "dmVuX2NsaWVudDI6OTk5OQ=="
          :bl   "YmxfY2xpZW50OjEwMDE="
          :bad  "bad_token"}
 :inter-suite-delay-ms 0}
```

Then:

```bash
cd ../clj-oa3-test
clojure -M:test --skip-meta :auth --focus :programs --focus :events --focus :subscriptions --focus :notifiers
# 64 tests, 0 failures
```

## Building

```bash
clojure -T:build ci       # tests + generate API docs + build JAR
clojure -T:build install  # install JAR locally
clojure -T:build deploy   # deploy to Clojars
```

API docs (Markdown) are generated by [codox-md](https://github.com/dcj/codox-md) and embedded in the JAR under `docs/energy.grid-coordination/clj-oa3-vtn/`. Consumers can browse them with the [deps-docs](/deps-docs) skill or extract from the JAR.

## Project Structure

```
src/openadr3/vtn/
  core.clj              — -main entry point
  system.clj            — Component system-map
  config.clj            — Config component
  handler.clj           — Legba routing-handler assembly (BL + VEN handler maps)
  handler/
    programs.clj         — Program CRUD
    events.clj           — Event CRUD (validates programID)
    subscriptions.clj    — Subscription CRUD (auto clientID)
    notifiers.clj        — GET /notifiers
    topics.clj           — MQTT topic discovery (12 endpoints)
    auth.clj             — Auth stubs
    docs.clj             — API docs: filtered OpenAPI spec + Scalar UI
    common.clj           — ID gen, metadata, pagination, error responses
  http.clj              — HttpServer Component (Jetty wrapper)
  middleware.clj         — Context path, JSON response, logging
  storage.clj           — VtnStorage protocol
  storage/memory.clj    — Atom-backed implementation
  storage/dynamo.clj    — DynamoDB implementation (eventStart GSIs, per-page caching)
  storage/validated.clj — Validating decorator (Malli schema checks on write)
  storage/notifying.clj — Notifying decorator (auto-publishes MQTT on C/U/D; ^:suppress-notify opt-out)
  nrepl.clj             — Optional nREPL server Component (production inspection)
  notifier.clj          — Notifier Component (MQTT topic routing, nil-safe)
  mqtt.clj              — MqttPublisher Component (Paho)
  schema.clj            — Wire-format Malli schemas, entity coercion bridge to clj-oa3
  time.clj              — RFC 3339 helpers
```

## Technology

| Concern | Library |
|---------|---------|
| HTTP routing | [Legba](https://github.com/mpenet/legba) — OpenAPI 3.1 spec → Ring routes |
| HTTP server | Jetty (ring-jetty-adapter) |
| Lifecycle | [Component](https://github.com/stuartsierra/component) |
| MQTT | [machine_head](https://github.com/clojurewerkz/machine_head) (Paho) |
| Validation | [Malli](https://github.com/metosin/malli) — wire-format entity schemas enforced at storage boundary + Legba OpenAPI validation |
| Entities | [clj-oa3](../clj-oa3) (shared schemas and coercion) |
| Time | [tick](https://github.com/juxt/tick) |
| nREPL | [nREPL](https://nrepl.org/) — optional, for production inspection |

## API Documentation (Scalar)

The VEN port automatically serves interactive API documentation:

- **`/api`** — Scalar API reference UI (browse endpoints, try requests)
- **`/openapi.json`** — Filtered OpenAPI spec (JSON, only includes active endpoints)

The spec is filtered at startup to match the active `:ven-routes` config, so users only see endpoints that actually respond. No build step — Scalar loads from CDN.

To customize the docs page title and description, add to your config:

```edn
{:docs-title "My Price Server API"
 :docs-description "Electricity pricing via OpenADR 3.1.0"}
```

## OpenAPI Spec

The VTN uses the OpenADR 3.1.0 specification at `resources/openadr3.yaml`. This is a local copy with patches for Legba compatibility and a few local extensions — see [doc/openapi-spec-legba-patches.md](doc/openapi-spec-legba-patches.md).

## Time and timezones

The VTN's internal canon is `java.time.ZonedDateTime` for every datetime field (`createdDateTime`, `modificationDateTime`, `intervalPeriod.start`) and `java.time.Duration` for every duration field (`intervalPeriod.duration`, `randomizeStart`). All zone-sensitive arithmetic happens on `ZonedDateTime`, so DST transitions are handled correctly by the IANA rules. This applies end-to-end: handlers, the `VtnStorage` protocol, the in-memory atom backend, and the DynamoDB backend all hold the same shape.

| Boundary | Format |
|---|---|
| OA3 wire (HTTP request/response, MQTT payload) | RFC 3339 / ISO 8601 strings. Inbound parsing accepts arbitrary offsets (`Z`, `+00:00`, `-07:00`, …) and the VTN-RI's space-separated form. Outbound serialisation is canonical UTC `Z`, per the OA3 spec convention. |
| In-memory entities | `ZonedDateTime` / `Duration`. The wire offset on the input is preserved on the in-memory ZDT (no re-zoning), but the DDB GSI key and outbound JSON are normalised to UTC `Z`. |
| DDB `:data` JSON blob | Canonical UTC `Z` ISO 8601 / ISO duration strings (the `clojure.data.json/JSONWriter` protocol is extended to encode `ZonedDateTime` and `Duration`). |
| DDB `:eventStart` GSI sort key | Canonical UTC `Z` ISO 8601 (lex-orderable for range queries regardless of the wire offset on the original input). |

### `/events` default filter is zone-neutral

When `GET /events` is called without `dateStart` / `dateEnd`, the VTN returns events whose interval `[start, start + duration]` overlaps `[now, now + 2 days]`. There is no UTC-midnight assumption — the same filter is correct in any deployment zone, including multi-zone scenarios where no single UTC day boundary is right for everyone. An explicit `dateStart` / `dateEnd` query keeps the existing BETWEEN-on-`intervalPeriod.start` semantics for callers that want a specific window.

### Operational stamps

`createdDateTime` and `modificationDateTime` are operational stamps generated by the VTN at write time. They are always UTC and never carry a non-UTC zone — they describe when the VTN itself observed the create/update, not anything about the event content's zone.

## Contributing

Issues, Discussions, and pull requests are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md) for the workflow (and the dev commands: tests, lint, nREPL). In short:

- **Questions, design discussion, spec-interpretation gaps** → [Discussions](https://github.com/grid-coordination/clj-oa3-vtn/discussions)
- **Confirmed bugs, validation/handler/storage fixes, doc errors** → [Issues](https://github.com/grid-coordination/clj-oa3-vtn/issues)
- **Patches** → pull requests; please open a Discussion or Issue first for non-trivial changes (new resource types, new storage backends, new spec versions, schema or middleware changes)

## License

Copyright © 2026 Clark Communications Corporation. Released under the [MIT License](LICENSE).
