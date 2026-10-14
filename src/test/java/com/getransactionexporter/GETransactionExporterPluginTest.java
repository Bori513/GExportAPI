package com.getransactionexporter;

import java.util.Arrays;
import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/** Development entry point. It is not included in the Plugin Hub production JAR. */
public final class GETransactionExporterPluginTest
{
	private GETransactionExporterPluginTest() { }

	public static void main(String[] args) throws Exception
	{
		// Bolt does not add -ea when launching a custom JAR. Keep RuneLite's
		// development-mode safety assertion enabled before loading the plugin.
		ClassLoader loader = GETransactionExporterPluginTest.class.getClassLoader();
		loader.setDefaultAssertionStatus(true);
		loader.setPackageAssertionStatus("net.runelite.client", true);
		ExternalPluginManager.loadBuiltin(GETransactionExporterPlugin.class);

		// Bolt passes launcher-only JVM options to custom JARs. RuneLite's direct
		// option parser does not accept those, so omit only the -J passthrough args.
		String[] clientArgs = Arrays.stream(args)
			.filter(argument -> !argument.startsWith("-J"))
			.toArray(String[]::new);
		RuneLite.main(clientArgs);
	}
}
