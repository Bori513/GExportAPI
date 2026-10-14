package com.getransactionexporter;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(GETransactionExporterConfig.GROUP)
public interface GETransactionExporterConfig extends Config
{
	String GROUP = "ge-transaction-exporter";

	@ConfigItem(
		keyName = "exportEnabled",
		name = "Enable API export",
		description = "Send completed GE transaction data to the configured third-party endpoint.",
		warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers",
		position = 0
	)
	default boolean exportEnabled() { return false; }

	@ConfigItem(
		keyName = "apiEndpoint",
		name = "API endpoint",
		description = "HTTPS endpoint, or HTTP only on localhost/127.0.0.1/::1.",
		position = 1
	)
	default String apiEndpoint() { return "http://127.0.0.1:8765/api/v1/ge-transactions"; }

	@ConfigItem(
		keyName = "apiToken",
		name = "API token",
		description = "Optional bearer token. Stored by RuneLite configuration; never logged.",
		secret = true,
		position = 2
	)
	default String apiToken() { return ""; }
}
