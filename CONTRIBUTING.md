# Contributing to clj-oa3-vtn

Thanks for your interest in contributing! This repo is a production OpenADR 3.1.0 [VTN](https://www.openadr.org/) (Virtual Top Node) server in Clojure. It uses [Legba](https://github.com/mpenet/legba) for OpenAPI-driven HTTP routing, [Component](https://github.com/stuartsierra/component) for lifecycle management, [machine_head](https://github.com/clojurewerkz/machine_head) (Paho) for MQTT publishing, and shares wire-format schemas and entity coercion with the [clj-oa3](https://github.com/grid-coordination/clj-oa3) client library. Storage is pluggable — an in-memory atom by default, with a DynamoDB backend for production.

## How to contribute

### Discussions

Use [Discussions](https://github.com/grid-coordination/clj-oa3-vtn/discussions) for:

- Questions about how to run the VTN — Component lifecycle, REPL workflow, the BL/VEN two-port split, MQTT broker setup, storage backends
- API and design judgment calls — "should the VTN expose X to VEN clients?" / "how should this resource type be modelled?" / "what should the default `:ven-routes` policy be?"
- OpenADR 3 spec interpretation that affects VTN behaviour — when the spec is ambiguous and you want to scope what the server should do
- Storage backend trade-offs — DynamoDB single-table layout, GSI design, eventStart indexing, in-memory persistence via duratom
- MQTT topic hierarchy questions — what to retain, who subscribes to what, broker ACL design
- Coordination with sibling repos: [clj-oa3](https://github.com/grid-coordination/clj-oa3) (client library, shared schemas), [clj-oa3-test](https://github.com/grid-coordination/clj-oa3-test) (integration suite), [openadr3-specification](https://github.com/grid-coordination/openadr3-specification) (upstream OpenAPI specs)
- Cross-implementation parity questions with [python-oa3](https://github.com/grid-coordination/python-oa3) or the OpenADR Alliance VTN-RI
- Sharing what you're building on top of the VTN

Discussions are open-ended — a good place to think out loud or scope something before it becomes a concrete change. Aligned outcomes from a Discussion often turn into one or more Issues.

### Issues

Use [Issues](https://github.com/grid-coordination/clj-oa3-vtn/issues) for actionable changes:

- Bugs in routing, request handling, or response shape against a real VEN or BL client
- Storage bugs — DynamoDB GSI mis-mapping, in-memory atom races, validation gaps surfaced by real payloads
- MQTT publishing bugs — wrong topic, missing operation, retained-flag misbehaviour, broker disconnects
- Notifier/topic-discovery gaps — `GET /notifiers` or `GET /notifiers/mqtt/topics/...` returning the wrong shape for a port or `:ven-routes` policy
- Validation gaps — Malli schema accepts something the VTN should reject (or rejects something it should accept) at the storage boundary
- Spec compliance gaps — endpoint shape, status codes, error responses, pagination, required fields
- New endpoint coverage (vens, resources, reports — currently stubbed) or new spec versions when the upstream OpenAPI specs expose them
- Documentation errors, unclear explanations, or stale prose in `README.md`, `doc/`, or namespace docstrings
- Discussion outcomes that have alignment and a clear scope

If you're not sure whether something is an Issue or a Discussion, start with a Discussion — we can convert it later.

### Pull requests

Pull requests are welcome.

- For small fixes (typos, broken links, single-test corrections, single-handler bug fixes), open a PR directly.
- For substantive changes (new resource types, new storage backends, new spec versions, schema or middleware changes), open a Discussion or Issue first so we can align on scope before you invest the effort.
- All changes pass `clojure -M:test` (Kaocha) and `clj-kondo --lint src test` cleanly.
- Match the existing tone and structure. The VTN composes Legba routing → handlers → storage decorators (validating, notifying) → backend (memory or DynamoDB) as roughly orthogonal layers; patches that fit cleanly into one layer without leaking concerns across them are the easiest to land.
- One commit per logical change is fine; we don't require squash or any particular branch naming.

## Development

```bash
clojure -M:test                 # run the Kaocha unit test suite (offline, in-memory storage)
clojure -M:nrepl                # nREPL on the port written to .nrepl-port — then `(start)` in the REPL
clj-kondo --lint src test       # lint
```

REPL workflow with [Component](https://github.com/stuartsierra/component):

```clojure
(require '[user :refer [start stop reset status]])
(start)   ;; BL on 8081, VEN on 8080, MQTT to localhost:1883
(reset)   ;; stop + start with fresh state
(stop)
```

The OpenAPI spec at `resources/openadr3.yaml` is a local copy of the [OpenADR 3.1.0 spec](https://github.com/grid-coordination/openadr3-specification) with patches for Legba compatibility — see [`doc/openapi-spec-legba-patches.md`](doc/openapi-spec-legba-patches.md) for what was changed and why. When the upstream spec changes, re-vendor and reapply patches, then re-run the test suite to confirm the wire format still matches.

Integration testing against a running VTN happens in the sibling [clj-oa3-test](https://github.com/grid-coordination/clj-oa3-test) repo — point its `test-config.edn` at a local `(start)`-ed VTN and run its Kaocha suite.

Wire-format entity schemas and the raw→coerced coercion machinery live upstream in [clj-oa3](https://github.com/grid-coordination/clj-oa3); the VTN re-exports them and adds wire-format Malli schemas at the storage boundary (see `src/openadr3/vtn/schema.clj`). Changes to the shared shape generally belong in clj-oa3, not here.

## Code of conduct

Be respectful and constructive. We're a small project and appreciate everyone who takes the time to file an issue or send a PR.

## Important notice

This server is provided on an "as-is" basis. Updates and maintenance, including responses to issues filed on GitHub, will take place on an "as time and resources permit" basis. Server behaviour (HTTP responses, MQTT notifications, storage round-trips) is best-effort against the OpenADR 3 specification as published by the [OpenADR Alliance](https://www.openadr.org/) and vendored in the [openadr3-specification](https://github.com/grid-coordination/openadr3-specification) repo. This server is not authoritative for compliance certification — independent verification against the source specification is recommended for any operator running this VTN in a production setting.
