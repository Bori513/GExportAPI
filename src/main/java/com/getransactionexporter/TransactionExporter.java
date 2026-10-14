package com.getransactionexporter;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;

final class TransactionExporter
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private final Gson gson;
	private final OkHttpClient client;
	private final ScheduledExecutorService scheduler;
	private final GETransactionExporterConfig config;
	private final OfferTracker tracker;
	private final Logger log;
	private final Runnable completionHook;
	private final Set<String> inFlight = new HashSet<>();

	TransactionExporter(Gson gson, OkHttpClient baseClient, ScheduledExecutorService scheduler,
		GETransactionExporterConfig config, OfferTracker tracker, Logger log)
	{
		this(gson, baseClient, scheduler, config, tracker, log, () -> { });
	}

	TransactionExporter(Gson gson, OkHttpClient baseClient, ScheduledExecutorService scheduler,
		GETransactionExporterConfig config, OfferTracker tracker, Logger log, Runnable completionHook)
	{
		this.gson = gson;
		this.client = baseClient.newBuilder().connectTimeout(10, TimeUnit.SECONDS)
			.readTimeout(15, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build();
		this.scheduler = scheduler;
		this.config = config;
		this.tracker = tracker;
		this.log = log;
		this.completionHook = completionHook;
	}

	void retryPending()
	{
		if (!config.exportEnabled()) return;
		HttpUrl endpoint = EndpointValidator.validate(config.apiEndpoint());
		if (endpoint == null)
		{
			log.warn("GE export endpoint is invalid or insecure");
			return;
		}
		long now = System.currentTimeMillis();
		for (PersistedState.QueuedEvent queued : tracker.pending())
			if (!queued.permanentFailure && queued.nextAttemptAt <= now) send(queued, endpoint);
	}

	private void send(PersistedState.QueuedEvent queued, HttpUrl endpoint)
	{
		String id = queued.event.event_id;
		synchronized (inFlight) { if (!inFlight.add(id)) return; }
		Request.Builder builder = new Request.Builder().url(endpoint)
			.post(RequestBody.create(JSON, gson.toJson(queued.event)))
			.header("Content-Type", "application/json").header("Idempotency-Key", id);
		String token = config.apiToken();
		if (token != null && !token.trim().isEmpty() && token.indexOf('\r') < 0 && token.indexOf('\n') < 0)
			builder.header("Authorization", "Bearer " + token.trim());
		client.newCall(builder.build()).enqueue(new Callback()
		{
			@Override public void onFailure(Call call, IOException ex) { temporary(queued, 0); finish(); completionHook.run(); }
			@Override public void onResponse(Call call, Response response)
			{
				try (Response ignored = response)
				{
					int code = response.code();
					if (code >= 200 && code < 300)
					{
						try { tracker.delivered(id); log.debug("Exported GE event {}", id); }
						catch (IOException ex) { log.warn("GE export accepted but state update failed for event {}", id); }
					}
					else if ((code >= 400 && code < 500) && code != 408 && code != 429) permanent(queued, code);
					else temporary(queued, retryAfterSeconds(response));
				}
				finally { finish(); completionHook.run(); }
			}
			private void finish() { synchronized (inFlight) { inFlight.remove(id); } }
		});
	}

	private void permanent(PersistedState.QueuedEvent queued, int code)
	{
		try { tracker.markPermanent(queued); } catch (IOException ex) { log.warn("Could not persist permanent GE export failure"); }
		log.warn("GE export rejected with HTTP {}; event retained without automatic retry", code);
	}

	private void temporary(PersistedState.QueuedEvent queued, long retryAfterSeconds)
	{
		int nextAttempt = queued.attempts + 1;
		long exponential = Math.min(300L, 1L << Math.min(nextAttempt, 8));
		long delay = Math.max(exponential, Math.min(retryAfterSeconds, 3600L));
		try { tracker.markTemporary(queued, System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(delay)); }
		catch (IOException ex) { log.warn("Could not persist GE export retry state"); }
		scheduler.schedule(this::retryPending, delay, TimeUnit.SECONDS);
	}

	static long retryAfterSeconds(Response response)
	{
		String value = response.header("Retry-After");
		if (value == null) return 0;
		try { return Math.max(0, Long.parseLong(value.trim())); }
		catch (NumberFormatException ignored) { return 0; }
	}
}
