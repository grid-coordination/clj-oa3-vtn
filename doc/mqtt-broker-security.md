# MQTT Broker Security

The VTN uses MQTT to push real-time notifications to VEN and BL clients.
The broker must be configured to enforce topic-level access control since
VEN clients are anonymous and untrusted.

## Actor Model

| Actor | Auth | Direction | Topic Access |
|---|---|---|---|
| VTN publisher | Authenticated | Publish | `OpenADR/3.1.0/#` (all topics) |
| VEN client | Anonymous | Subscribe | `OpenADR/3.1.0/programs/#`, `OpenADR/3.1.0/events/#` |
| BL client | Authenticated | Subscribe | `OpenADR/3.1.0/#` (all topics) |

**VEN clients** are public, anonymous, read-only subscribers. They should
only see programs and events notifications. Subscription state, VEN
registration, and other internal topics must be invisible.

**BL clients** are internal, authenticated subscribers. They can see all
topics including subscription lifecycle events.

**The VTN publisher** connects with credentials and publishes to all
topics. Even though VEN subscription handlers don't emit MQTT
notifications (defense in depth), BL-originated subscription changes
do publish, so broker ACL is the enforcement boundary.

## VTN Configuration

The VTN publisher authenticates to the broker via config:

```clojure
{:mqtt-broker-url  "mqtt://broker.internal:1883"
 :mqtt-username    "vtn-publisher"
 :mqtt-password    "secret"}
```

BL client credentials can be advertised via the notifiers endpoint
by setting `:bl-notifiers` config with authentication details.

## Broker ACL Configuration

### Mosquitto

```
# /etc/mosquitto/acl_file

# VTN publisher — full publish access
user vtn-publisher
topic write OpenADR/3.1.0/#

# BL clients — full read access
user bl-client
topic read OpenADR/3.1.0/#

# Anonymous (VEN clients) — restricted read access
topic read OpenADR/3.1.0/programs/#
topic read OpenADR/3.1.0/events/#
```

In `mosquitto.conf`:
```
allow_anonymous true
acl_file /etc/mosquitto/acl_file
password_file /etc/mosquitto/passwd
```

### NanoMQ

```hocon
authorization {
  sources = [
    {
      match = { username = "vtn-publisher" }
      rules = [
        { permit = "allow", action = "publish", topics = ["OpenADR/3.1.0/#"] }
      ]
    }
    {
      match = { username = "bl-client" }
      rules = [
        { permit = "allow", action = "subscribe", topics = ["OpenADR/3.1.0/#"] }
      ]
    }
    {
      match = { username = "" }  # anonymous
      rules = [
        { permit = "allow", action = "subscribe", topics = ["OpenADR/3.1.0/programs/#", "OpenADR/3.1.0/events/#"] }
        { permit = "deny",  action = "all",       topics = ["#"] }
      ]
    }
  ]
}
```

## Defense in Depth

The VTN enforces topic boundaries at multiple layers:

1. **Handler layer** — VEN subscription handlers don't receive a notifier,
   so subscription C/U/D on the VEN port never publishes to MQTT.

2. **Topic discovery** — The VEN port does not expose
   `/notifiers/mqtt/topics/subscriptions`, so VEN clients cannot discover
   subscription topics via the API.

3. **Broker ACL** — Anonymous MQTT subscribers are restricted to
   `programs/#` and `events/#` topic trees. Even if a VEN client guesses
   the subscription topic name, the broker rejects the subscribe request.

## WebSocket Support

For browser-based VEN clients, the broker should expose a WebSocket
listener. The VTN supports `ws://` and `wss://` URI schemes in config:

```clojure
{:mqtt-public-url     "mqtt://mqtt.example.com:1883"
 :mqtt-public-url-tls "mqtts://mqtt.example.com:8883"
 :mqtt-public-url-ws  "ws://mqtt.example.com:8083"
 :mqtt-public-url-wss "wss://mqtt.example.com:8084"}
```

All configured URIs are advertised in the `/notifiers` response.
The same ACL rules apply regardless of transport.
