package com.getransactionexporter;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.api.GrandExchangeOfferState;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import static org.junit.Assert.*;

public class TransactionExporterTest
{
	@Test public void successfulDeliveryRemovesPending() throws Exception { assertResponse(204, false, 0); }
	@Test public void temporaryFailureIsRetriedLater() throws Exception { assertResponse(500, false, 1); }
	@Test public void permanentClientFailureStopsRetrying() throws Exception { assertResponse(400, true, 0); }
	@Test public void tooManyRequestsIsTemporary() throws Exception { assertResponse(429, false, 1); }

	@Test public void sendsIdempotencyAndBearerHeadersWithoutLoggingToken() throws Exception
	{
		MemoryStore store = new MemoryStore();
		OfferTracker tracker = queuedTracker(store);
		AtomicReference<Request> recorded = new AtomicReference<>();
		CountDownLatch ended = new CountDownLatch(1);
		OkHttpClient client = client(200, recorded, ended, null);
		ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
		try
		{
			new TransactionExporter(new Gson(), client, scheduler, config("top-secret-token"), tracker,
				LoggerFactory.getLogger("export-test"), ended::countDown).retryPending();
			assertTrue(ended.await(3, TimeUnit.SECONDS));
			assertEquals("Bearer top-secret-token", recorded.get().header("Authorization"));
			assertEquals(64, recorded.get().header("Idempotency-Key").length());
		}
		finally { scheduler.shutdown(); }
	}

	private void assertResponse(int code, boolean permanent, int attempts) throws Exception
	{
		MemoryStore store = new MemoryStore();
		OfferTracker tracker = queuedTracker(store);
		CountDownLatch ended = new CountDownLatch(1);
		OkHttpClient client = client(code, new AtomicReference<>(), ended, code == 429 ? "60" : null);
		ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
		try
		{
			new TransactionExporter(new Gson(), client, scheduler, config(""), tracker,
				LoggerFactory.getLogger("export-test"), ended::countDown).retryPending();
			assertTrue(ended.await(3, TimeUnit.SECONDS));
			if (code >= 200 && code < 300) assertTrue(tracker.state().pending.isEmpty());
			else
			{
				assertEquals(1, tracker.state().pending.size());
				assertEquals(permanent, tracker.state().pending.get(0).permanentFailure);
				assertEquals(attempts, tracker.state().pending.get(0).attempts);
				if (code == 429) assertTrue(tracker.state().pending.get(0).nextAttemptAt >= System.currentTimeMillis() + 50_000);
			}
		}
		finally { scheduler.shutdown(); }
	}

	private static OkHttpClient client(int code, AtomicReference<Request> recorded, CountDownLatch ended, String retryAfter)
	{
		Interceptor interceptor = chain ->
		{
			recorded.set(chain.request());
			Response.Builder response = new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
				.code(code).message("test").body(ResponseBody.create(null, new byte[0]));
			if (retryAfter != null) response.header("Retry-After", retryAfter);
			return response.build();
		};
		return new OkHttpClient.Builder().addInterceptor(interceptor).build();
	}

	private static OfferTracker queuedTracker(MemoryStore store) throws Exception
	{
		OfferTracker tracker = new OfferTracker(store, id -> "Coal");
		tracker.observe(new OfferSnapshot(0, GrandExchangeOfferState.BUYING, 453, 0, 10, 150), 1000);
		tracker.observe(new OfferSnapshot(0, GrandExchangeOfferState.BOUGHT, 453, 10, 10, 150), 2000);
		return tracker;
	}

	private static GETransactionExporterConfig config(String token)
	{
		return new GETransactionExporterConfig()
		{
			@Override public boolean exportEnabled() { return true; }
			@Override public String apiEndpoint() { return "https://example.com/api"; }
			@Override public String apiToken() { return token; }
		};
	}

	private static final class MemoryStore implements StateStore
	{
		private final Gson gson = new Gson();
		private String json;
		@Override public PersistedState load() { return json == null ? new PersistedState() : gson.fromJson(json, PersistedState.class); }
		@Override public void save(PersistedState state) { json = gson.toJson(state); }
	}
}
