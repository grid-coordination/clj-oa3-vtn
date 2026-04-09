# clj-oa3-vtn

Production OpenADR 3.1.0 Virtual Top Node (VTN) server in Clojure.

## Overview

An OpenADR 3 VTN that serves as a **public price server** — publishing electricity pricing programs and events over HTTP and MQTT. Business logic clients (BL clients) push pricing data in; VEN clients read it and subscribe for notifications.

### Two-Port Architecture

The VTN exposes two HTTP ports with different access levels:

| Port | Role | Access |
|------|------|--------|
| **8081** (BL) | Business Logic | Full CRUD on programs, events, subscriptions |
| **8080** (VEN) | Virtual End Node | Read programs/events, manage subscriptions |

BL clients run co-located with the VTN and are trusted by network topology — no authentication in Phase 1. VEN clients are public-facing and can only read data and create subscriptions for MQTT notifications.

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

# Create an event with pricing
PROGRAM_ID=<id-from-above>
curl -X POST http://localhost:8081/openadr3/3.1.0/events \
  -H 'Content-Type: application/json' \
  -d "{\"programID\":\"$PROGRAM_ID\",\"intervals\":[{\"id\":0,\"payloads\":[{\"type\":\"PRICE\",\"values\":[0.25]}]}]}"

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

Default config in `resources/config.edn`:

```edn
{:ven-port 8080
 :bl-port 8081
 :context-path "/openadr3/3.1.0"
 :mqtt-broker-url "tcp://localhost:1883"
 :mqtt-retained false
 :storage-backend :memory  ;; :memory (default) or :dynamodb

 ;; For :memory backend — optional file persistence via duratom:
 ;; :storage-file-path "/tmp/vtn-store.edn"

 ;; For :dynamodb backend:
 ;; :dynamodb-table "openadr3"
 ;; :dynamodb-region "us-west-2"
 ;; :dynamodb-ensure-table true  ;; auto-create table (dev only)

 ;; Per-port notifier configuration.
 ;; Controls what GET /notifiers returns on each port.
 :ven-notifiers {:MQTT {:authentication {:method "ANONYMOUS"}}}
 :bl-notifiers  {:MQTT {:authentication {:method "ANONYMOUS"}}
                 :WEBHOOK true}}
```

The `:ven-notifiers` and `:bl-notifiers` maps control what `GET /notifiers` returns on each port. The VEN port advertises MQTT only (no webhook support for public price consumers). The BL port advertises both. MQTT broker URL and serialization are filled in automatically from `:mqtt-broker-url`.

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
:config          → loads config.edn
:storage         → AtomStorage (in-memory)
:mqtt-publisher  → Paho MQTT client (depends on :config)
:notifier        → routes C/U/D to MQTT topics (depends on :mqtt-publisher)
:http-server-bl  → Jetty on BL port, full CRUD routes (depends on :storage, :notifier, :config)
:http-server-ven → Jetty on VEN port, read+subscribe routes (depends on :storage, :notifier, :config)
```

### nREPL Port

Port **7892** (ecosystem convention: clj-oa3=7889, clj-oa3-client=7890, clj-oa3-test=7891).

## Testing

### Unit Tests

```bash
clojure -M:test
# 38 tests, 167 assertions
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
    common.clj           — ID gen, metadata, pagination, error responses
  http.clj              — HttpServer Component (Jetty wrapper)
  middleware.clj         — Context path, JSON response, logging
  storage.clj           — VtnStorage protocol
  storage/memory.clj    — Atom-backed implementation
  notifier.clj          — Notifier Component (MQTT topic routing)
  mqtt.clj              — MqttPublisher Component (Paho)
  schema.clj            — Entity coercion bridge to clj-oa3
  time.clj              — RFC 3339 helpers
```

## Technology

| Concern | Library |
|---------|---------|
| HTTP routing | [Legba](https://github.com/mpenet/legba) — OpenAPI 3.1 spec → Ring routes |
| HTTP server | Jetty (ring-jetty-adapter) |
| Lifecycle | [Component](https://github.com/stuartsierra/component) |
| MQTT | [machine_head](https://github.com/clojurewerkz/machine_head) (Paho) |
| Validation | [Malli](https://github.com/metosin/malli) + Legba OpenAPI validation |
| Entities | [clj-oa3](../clj-oa3) (shared schemas and coercion) |
| Time | [tick](https://github.com/juxt/tick) |

## OpenAPI Spec

The VTN uses the OpenADR 3.1.0 specification at `resources/openadr3.yaml`. This is a local copy with two patches for Legba compatibility — see [doc/openapi-spec-legba-patches.md](doc/openapi-spec-legba-patches.md).

## License

Copyright © 2026
