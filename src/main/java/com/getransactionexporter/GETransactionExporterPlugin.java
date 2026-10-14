package com.getransactionexporter;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@PluginDescriptor(
	name = "GE Transaction Exporter",
	description = "Exports observed completed Grand Exchange transactions to a configured API",
	tags = {"grand exchange", "ge", "export", "api", "transactions"},
	internalName = "ge-transaction-exporter"
)
public class GETransactionExporterPlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(GETransactionExporterPlugin.class);
	@Inject private Gson gson;
	@Inject private OkHttpClient httpClient;
	@Inject private ScheduledExecutorService scheduler;
	@Inject private GETransactionExporterConfig config;
	@Inject private ItemManager itemManager;
	private volatile OfferTracker tracker;
	private volatile TransactionExporter exporter;

	@Provides
	GETransactionExporterConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GETransactionExporterConfig.class);
	}

	@Override
	protected void startUp()
	{
		scheduler.execute(() ->
		{
			try
			{
				OfferTracker newTracker = new OfferTracker(new FileStateStore(gson, getPluginDirectory()));
				TransactionExporter newExporter = new TransactionExporter(gson, httpClient, scheduler, config, newTracker, log);
				tracker = newTracker;
				exporter = newExporter;
				newExporter.retryPending();
			}
			catch (IOException | RuntimeException ex)
			{
				log.warn("Unable to initialize GE Transaction Exporter persistence: {}", ex.getMessage());
			}
		});
	}

	@Override
	protected void shutDown()
	{
		exporter = null;
		tracker = null;
	}

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		OfferTracker current = tracker;
		if (current == null) return;
		// GrandExchangeOfferChanged is delivered on the client thread. ItemManager
		// cache access must stay here; the immutable snapshot is safe to persist later.
		String itemName = itemManager.getItemComposition(event.getOffer().getItemId()).getName();
		OfferSnapshot snapshot = OfferSnapshot.from(event.getSlot(), event.getOffer(), itemName);
		long observed = System.currentTimeMillis();
		scheduler.execute(() ->
		{
			try
			{
				TransactionEvent queued = current.observe(snapshot, observed);
				TransactionExporter currentExporter = exporter;
				if (queued != null && currentExporter != null) currentExporter.retryPending();
			}
			catch (IOException | ArithmeticException | IllegalArgumentException ex)
			{
				log.warn("Could not persist observed GE offer: {}", ex.getMessage());
			}
		});
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (GETransactionExporterConfig.GROUP.equals(event.getGroup()) && exporter != null)
			scheduler.execute(exporter::retryPending);
	}
}
