# GE Transaction Exporter

GE Transaction Exporter is a small open-source RuneLite Plugin Hub plugin with one job: observe the local player's own Grand Exchange slots and POST a transaction after an observed order completes or a partially filled order is cancelled.

> **API export is disabled by default.** Enabling it sends completed GE transaction information, and the user's IP address as part of normal networking, to the configured third-party endpoint. Users are responsible for trusting and securing that endpoint. Plain HTTP is accepted only for a service on the same machine; every non-loopback endpoint must use HTTPS.

The plugin exports no recommendations, fetches no market prices, performs no game interaction, changes no widgets, and has no overlay, sidebar, dashboard, server, or command channel.

## What is exported

An event is queued only for:

- a completed buy (`BOUGHT`);
- a completed sell (`SOLD`);
- a cancelled buy with at least one item bought; or
- a cancelled sell with at least one item sold.

A zero-fill cancellation is not exported. The plugin first persists the event, then sends it asynchronously. A 2xx response marks it delivered. Network errors, 408, 429, and 5xx responses use bounded exponential backoff (up to five minutes; a numeric `Retry-After` up to one hour is respected). Other 4xx responses are retained locally as permanent failures and are not retried indefinitely.

The durable queue is capped at 256 events and the state file at 2 MiB. If full, the newest terminal observation is not silently discarded: it is left unacknowledged and an explicit warning is logged so a repeated RuneLite GE event can be captured after capacity is restored. Up to 4,096 delivered IDs are retained; tracked terminal slot state provides an additional deduplication barrier.

## JSON schema

```json
{
  "schema_version": 1,
  "event_id": "64-character-lowercase-sha256",
  "offer_slot": 2,
  "side": "BUY",
  "item_id": 453,
  "item_name": "Coal",
  "quantity": 1000,
  "unit_price": 138,
  "total_coins": 138000,
  "offer_state": "COMPLETED",
  "observed_at": 1791979200,
  "completed_at": 1791979500
}
```

| Field | Meaning |
| --- | --- |
| `schema_version` | Integer payload version, currently `1`. |
| `event_id` | Deterministic SHA-256 identity derived from the persisted offer lifecycle and stable offer properties. |
| `offer_slot` | RuneLite GE slot, 0–7. |
| `side` | `BUY` or `SELL`. |
| `item_id`, `item_name` | Cache item ID and name resolved locally through RuneLite's `ItemManager`. |
| `quantity` | Quantity RuneLite reports bought or sold at terminal observation. |
| `unit_price` | Actual average settlement price: RuneLite's cumulative coins exchanged divided by filled quantity. If fills occurred at different prices, integer division truncates any fractional average. |
| `total_coins` | Actual cumulative coins spent or received as reported by RuneLite, stored as a `long`. |
| `offer_state` | `COMPLETED` or `CANCELLED_PARTIAL`. |
| `observed_at` | Unix seconds when this plugin first observed the active offer lifecycle. RuneLite does not expose the original offer creation time. |
| `completed_at` | Unix seconds when this plugin observed the terminal state, not a server-authored exchange timestamp. |

The receiver **must enforce uniqueness on `event_id`**. Every request also includes `Idempotency-Key: <event_id>`. An optional configured token is sent as `Authorization: Bearer <token>`. The plugin ignores all response bodies and never accepts gameplay commands.

## Receiver contract

Request: `POST`, `Content-Type: application/json`. Any 2xx response means accepted. Suggested responses:

- `201 Created` or `204 No Content`: accepted, including an idempotent duplicate;
- `400 Bad Request`: permanently invalid payload;
- `401 Unauthorized` / `403 Forbidden`: permanently rejected credentials;
- `408 Request Timeout`, `429 Too Many Requests`, or `5xx`: temporary failure; optionally include numeric `Retry-After` seconds.

Example receiver test:

```sh
curl --fail-with-body -X POST https://receiver.example/api/v1/ge-transactions \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef' \
  -H 'Authorization: Bearer YOUR_TOKEN' \
  --data '{"schema_version":1,"event_id":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef","offer_slot":2,"side":"BUY","item_id":453,"item_name":"Coal","quantity":1000,"unit_price":138,"total_coins":138000,"offer_state":"COMPLETED","observed_at":1791979200,"completed_at":1791979500}'
```

Example PostgreSQL table:

```sql
CREATE TABLE ge_transactions (
  event_id text PRIMARY KEY,
  schema_version integer NOT NULL,
  offer_slot smallint NOT NULL,
  side text NOT NULL CHECK (side IN ('BUY', 'SELL')),
  item_id integer NOT NULL,
  item_name text NOT NULL,
  quantity integer NOT NULL,
  unit_price bigint NOT NULL,
  total_coins bigint NOT NULL,
  offer_state text NOT NULL CHECK (offer_state IN ('COMPLETED', 'CANCELLED_PARTIAL')),
  observed_at bigint NOT NULL,
  completed_at bigint NOT NULL,
  received_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (event_id)
);
```

## Reliability and limitations

On first observation of a slot, an already-terminal offer becomes a baseline and is not exported. An active offer becomes eligible only after the plugin observes it, and this lifecycle state survives RuneLite/plugin restarts. Repeated events, reconnects, world hops, and reopening the GE converge on the same ID. Slot reuse starts a new persisted generation, including reuse with otherwise identical order properties.

RuneLite exposes only the current eight GE slots. It cannot provide arbitrary historical transactions that the plugin never observed. Clearing or losing the plugin's local state also removes its knowledge of prior lifecycles. Observation times are honest local approximations, not exchange timestamps.

## Configuration and token storage

RuneLite configuration provides the opt-in toggle, endpoint, and optional secret token. The token is marked `secret`, excluded from payload persistence, and never logged. RuneLite configuration is not an operating-system credential vault; users wanting stronger isolation should use a loopback receiver that owns the upstream credential.

## Build and local testing

Requirements: JDK 11 and Gradle 8.x. No third-party runtime dependencies were added; Gson, OkHttp, and dependency injection come from RuneLite.

```sh
gradle clean test build
gradle shadowJar
gradle run
```

For a Jagex account, follow RuneLite's [Using Jagex Accounts](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts) guide. For Bolt Launcher, enable its developer/plugin-development workflow, point it at this repository or the built development JAR as supported by the installed Bolt version, launch RuneLite, enable **GE Transaction Exporter**, keep export disabled initially, then configure a trusted loopback receiver and enable it. Verify a new small offer lifecycle in game; a clean JVM start alone is not an in-game passing test.

## Plugin Hub submission

1. Test a clean build and real in-game buy, sell, partial cancellation, restart, and retry flow.
2. Publish this repository publicly under the included BSD 2-Clause license.
3. Fork `runelite/plugin-hub`, add the repository and commit entry following its current README, and run its prescribed checks.
4. Open the Plugin Hub pull request only after local in-game confirmation. Do not commit `build/`, shadow JARs, tokens, or local state.

See [PRIVACY.md](PRIVACY.md) for the complete data statement.
