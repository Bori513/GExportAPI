# Privacy

GE Transaction Exporter is opt-in and disabled by default. When enabled, it sends only the following data for the local player's Grand Exchange order after the plugin has observed that order reach a supported terminal state: schema version, deterministic event ID, GE slot, buy/sell side, item ID and locally resolved name, filled quantity, offer unit price, derived coin total, terminal-state classification, first-observed time, and terminal-observed time.

The destination receives the user's IP address as a normal consequence of HTTP networking. An optional token is sent only to that destination in the `Authorization` header. RuneLite stores the token in its configuration; the plugin marks it secret, never persists it in the queue, and never logs it.

The plugin does not collect or transmit account names, credentials, chat, friends, hardware identifiers, session tokens, bank contents, unrelated inventory contents, market-price data, or other players' data. It does not receive commands or configuration from the API and ignores response bodies.

Queue and deduplication state is stored locally in RuneLite's plugin-data directory. Uninstalling the plugin does not necessarily remove that local data. Operators of a configured endpoint control their own retention and security practices; users must trust them before enabling export.
