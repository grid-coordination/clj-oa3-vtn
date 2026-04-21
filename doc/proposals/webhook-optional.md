# Make WEBHOOK Optional in `notifiersResponse`

**Target spec:** OpenADR 3.1.1 (`openadr3.yaml`)
**Status:** Filed as [oadr3-org/specification#419](https://github.com/oadr3-org/specification/issues/419)
**Scope:** `GET /notifiers` response schema (`notifiersResponse`)
**Related:** [#369](https://github.com/oadr3-org/specification/issues/369) (WebSocket notifier), [PR #416](https://github.com/oadr3-org/specification/pull/416) (merged)

---

## 1. Use case

A VTN operator deploys a **public-facing price server** for VEN clients. The server publishes programs and events and supports notifications via MQTT, WebSocket, or both — but not webhooks. The operator has no intention of implementing webhook callbacks: the VTN does not initiate outbound HTTP connections to arbitrary VEN-supplied URLs.

Reasons an operator might choose not to support WEBHOOK:

- **Client reachability.** Webhook delivery requires the VEN to accept inbound HTTP POST requests. Virtually no consumer or business firewall allows this. VENs behind NAT, residential gateways, or corporate firewalls — which is to say, most VENs — cannot receive webhooks without additional infrastructure (reverse proxies, tunneling services). MQTT and WebSocket both use outbound connections initiated by the client, which traverse firewalls naturally.
- **Security posture.** Webhook delivery requires the VTN to make outbound HTTP requests to URLs supplied by clients. In a public deployment, this creates an SSRF surface — the VTN becomes an HTTP request proxy controlled by any authenticated client. On an unauthenticated or lightly authenticated public VTN, this is a DDoS amplification vector: an attacker creates subscriptions pointing `callbackUrl` at a victim, and the VTN dutifully delivers HTTP POST requests to the target on every event change. MQTT and WebSocket both invert this responsibility: clients connect to the VTN or broker, not the other way around.
- **Operational simplicity.** Webhook delivery requires retry queues, dead-letter handling, circuit breakers, and per-callback TLS configuration. MQTT delivery is fire-and-publish to a single broker with QoS guarantees handled by the protocol. WebSocket delivery pushes over an already-established connection.
- **Scale.** A price server serving thousands of VENs would need to make an outbound HTTP request to each VEN's callback URL on every event change. MQTT and WebSocket fan-out is handled at the protocol level.

With MQTT, the VEN does not create a `subscription` object via the REST API. Instead, it connects directly to the MQTT broker and subscribes to well-known topics discovered via `GET /notifiers/mqtt/topics/*`. The VTN publishes notifications to these topics; the broker handles fan-out. No `callbackUrl`, no `mechanism` field, no `/subscriptions` endpoint involved.

The VTN needs to advertise its notification capabilities honestly so that clients can make informed decisions about how to receive updates.

---

## 2. Problem statement

The 3.1.1 specification (after the WebSocket addition in PR #416) treats notification bindings inconsistently: `WEBSOCKET` is optional, but `WEBHOOK` is required and must be `true`.

### 2.1 Current `notifiersResponse` schema (3.1.1)

```yaml
notifiersResponse:
  type: object
  description: Provides details of each notifier binding supported
  required:
    - WEBHOOK
  properties:
    WEBHOOK:
      type: boolean
      description: 'Currently MUST be true'
      example: true
    WEBSOCKET:
      type: boolean
      description: 'true if VTN supports WebSocket notifier binding'
      example: true
    MQTT:
      $ref: '#/components/schemas/mqttNotifierBindingObject'
```

Three properties, three different treatments:

| Property | Required? | Can be false/absent? |
|----------|-----------|---------------------|
| `WEBHOOK` | **yes** | no — "Currently MUST be true" |
| `WEBSOCKET` | no | yes |
| `MQTT` | no | yes |

There is no technical reason for this asymmetry. All three are notification bindings. The `WEBSOCKET` property added in PR #416 correctly models the pattern: an optional boolean that a VTN includes when it supports the binding. `WEBHOOK` should follow the same pattern.

### 2.2 Consequences

1. **VTNs must lie.** An MQTT-only VTN returns `{"WEBHOOK": true, ...}` from `GET /notifiers`, advertising a capability it does not implement. A client that trusts this response and creates a subscription with `mechanism: WEBHOOK` will silently receive no notifications.

2. **Alternatively, VTNs must break the spec.** A VTN that returns `{"WEBHOOK": false, "MQTT": {...}}` is honest but technically non-compliant (the description says "Currently MUST be true"). A VTN that omits `WEBHOOK` entirely fails schema validation (the field is required).

3. **The discovery endpoint can't discover.** The point of `GET /notifiers` is to let clients discover what notification channels are available. If `WEBHOOK` is always `true` by fiat, the endpoint provides no information about that channel — clients cannot distinguish a VTN that delivers webhooks from one that claims to but doesn't.

4. **Inconsistency with 3.1.1's own precedent.** PR #416 established the correct pattern: `WEBSOCKET` is an optional boolean. Applying a different rule to `WEBHOOK` is an inconsistency that will confuse implementers and spec readers.

---

## 3. Proposed change

Remove `WEBHOOK` from `required` in `notifiersResponse` and update its description to match the `WEBSOCKET` pattern.

### 3.1 OpenAPI diff

```yaml
# Before (3.1.1)
notifiersResponse:
  type: object
  description: Provides details of each notifier binding supported
  required:
    - WEBHOOK
  properties:
    WEBHOOK:
      type: boolean
      description: 'Currently MUST be true'
      example: true
    WEBSOCKET:
      type: boolean
      description: 'true if VTN supports WebSocket notifier binding'
      example: true
    MQTT:
      $ref: '#/components/schemas/mqttNotifierBindingObject'

# After
notifiersResponse:
  type: object
  description: Provides details of each notifier binding supported.
  properties:
    WEBHOOK:
      type: boolean
      description: 'true if VTN supports webhook (HTTP callback) notification delivery'
      example: true
    WEBSOCKET:
      type: boolean
      description: 'true if VTN supports WebSocket notifier binding'
      example: true
    MQTT:
      $ref: '#/components/schemas/mqttNotifierBindingObject'
```

Changes:
- Remove the `required` block entirely (no properties are required).
- Replace `"Currently MUST be true"` with a description that mirrors the `WEBSOCKET` pattern.

### 3.2 Behavior

- **Response shape is unchanged.** The response is still a `notifiersResponse` object. VTNs that support all three channels continue to return all three. VTNs that only support MQTT return `{"MQTT": {...}}`. VTNs that support MQTT and WebSocket return `{"WEBSOCKET": true, "MQTT": {...}}`.
- **Client capability discovery.** A client checks `GET /notifiers` before creating a subscription. The presence and value of `WEBHOOK`, `WEBSOCKET`, and `MQTT` tells the client which `mechanism` values are valid and whether `callbackUrl` is needed. This is exactly how `WEBSOCKET` already works post-PR #416.
- **`mechanism` field interaction.** PR #416 added `mechanism` to `objectOperations` with values `WEBHOOK` and `WEBSOCKET`. A VTN that does not support WEBHOOK should reject subscriptions with `mechanism: WEBHOOK` (or the default, since `WEBHOOK` is the default value) with `400 Bad Request`. This is standard server-side validation — no schema change needed beyond what PR #416 already established.
- **Authorization.** No change.

---

## 4. Backward compatibility

This is a **relaxation** — a previously required field becomes optional.

- **Existing VTNs that support WEBHOOK** continue to return `{"WEBHOOK": true, ...}`. Their responses remain valid. No code changes required.
- **Existing clients that check for WEBHOOK** should already be checking the boolean value, not just the key's presence. Since the field previously "MUST be true," any client that only checks presence (not value) is arguably already broken — it would accept `{"WEBHOOK": false}` as webhook-capable.
- **No response schemas change structurally.** The same fields exist; one fewer is required.
- **No security scopes change.**
- **No path structure changes.**
- **No interaction with PR #416's changes.** The `mechanism` field, `callbackUrl` optionality, and `WEBSOCKET` property are all orthogonal to this change.

---

## 5. Rejected alternatives

### 5.1 Keep WEBHOOK required but allow `false`

Pros: clients always see the key.
Cons: a required boolean that can be `false` is semantically identical to an optional boolean. More importantly, it preserves the asymmetry with `WEBSOCKET`, which is optional. **Rejected** — consistency with the established pattern is more valuable than a guaranteed key.

### 5.2 Make all three bindings required

Pros: symmetric; clients always see all three keys.
Cons: this would be a breaking change for VTNs that don't return `WEBSOCKET` today (which is most of them, since PR #416 just merged). It also doesn't scale — every future binding would need to be added to `required`, forcing all existing VTNs to update. **Rejected.**

### 5.3 Status quo

Pros: no spec change.
Cons: forces VTNs to misrepresent their capabilities and creates an internal inconsistency where `WEBHOOK` and `WEBSOCKET` — two boolean binding flags in the same object — follow different rules for no discernible reason. **Rejected.**

---

## 6. Impact on `Definition.md`

The existing notifier binding text should be updated to clarify that all bindings are optional and discoverable:

> A VTN advertises which notification bindings it supports via `GET /notifiers`. Each binding — WEBHOOK, WEBSOCKET, MQTT — is independently optional. Clients SHOULD check this response before creating subscriptions to determine which `mechanism` values are valid and whether `callbackUrl` is needed.

---

## 7. Follow-on work (out of scope for this proposal)

- **Default `mechanism` behavior.** PR #416 added `mechanism` to `objectOperations` with a default of `WEBHOOK`. If `WEBHOOK` is no longer guaranteed to be supported, a VTN that does not support WEBHOOK must reject subscriptions that rely on this default. Changing the default to require explicit selection would be cleaner but is a breaking change and should be considered separately.
- **Clarify the relationship between MQTT and `/subscriptions`.** MQTT notification delivery operates independently of the `/subscriptions` REST API — clients subscribe to MQTT topics directly on the broker, not via `POST /subscriptions`. The `mechanism` enum (`WEBHOOK`, `WEBSOCKET`) is therefore only relevant to `/subscriptions`-based notification channels. This is implicit in the current spec but could be stated explicitly to avoid confusion.

---

## 8. Summary

| | Before (3.1.1) | After |
|---|---|---|
| `WEBHOOK` in `notifiersResponse` | required, "Currently MUST be true" | optional boolean (same as `WEBSOCKET`) |
| `WEBSOCKET` in `notifiersResponse` | optional boolean | unchanged |
| `MQTT` in `notifiersResponse` | optional object | unchanged |
| MQTT-only VTN compliance | must return `WEBHOOK: true` (misleading) | omit `WEBHOOK` or return `false` |
| Schema changes | — | remove `WEBHOOK` from `required`, update description |
| Breaking changes | — | none (relaxation only) |
| Existing WEBHOOK VTNs | — | unchanged behavior |
