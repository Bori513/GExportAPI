package com.getransactionexporter;

import com.google.gson.Gson;
import java.io.IOException;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;

import static org.junit.Assert.*;

public class OfferTrackerTest
{
	private static final long T = 1_791_979_200_000L;
	private final MemoryStore store = new MemoryStore();

	@Test public void completedBuy() throws Exception { assertEvent(GrandExchangeOfferState.BUYING, GrandExchangeOfferState.BOUGHT, "BUY", "COMPLETED"); }
	@Test public void completedSell() throws Exception { assertEvent(GrandExchangeOfferState.SELLING, GrandExchangeOfferState.SOLD, "SELL", "COMPLETED"); }
	@Test public void partialCancelledBuy() throws Exception { assertEvent(GrandExchangeOfferState.BUYING, GrandExchangeOfferState.CANCELLED_BUY, "BUY", "CANCELLED_PARTIAL"); }
	@Test public void partialCancelledSell() throws Exception { assertEvent(GrandExchangeOfferState.SELLING, GrandExchangeOfferState.CANCELLED_SELL, "SELL", "CANCELLED_PARTIAL"); }

	@Test public void zeroFillCancellationIsIgnored() throws Exception
	{
		OfferTracker tracker = tracker();
		tracker.observe(o(0, GrandExchangeOfferState.BUYING, 0), T);
		assertNull(tracker.observe(o(0, GrandExchangeOfferState.CANCELLED_BUY, 0), T + 1));
		assertTrue(tracker.state().pending.isEmpty());
	}

	@Test public void duplicateEventsAndRestartDoNotDuplicate() throws Exception
	{
		OfferTracker tracker = tracker();
		tracker.observe(o(0, GrandExchangeOfferState.BUYING, 5), T);
		TransactionEvent first = tracker.observe(o(0, GrandExchangeOfferState.BOUGHT, 10), T + 1);
		assertNotNull(first);
		assertNull(tracker.observe(o(0, GrandExchangeOfferState.BOUGHT, 10), T + 2));
		OfferTracker restarted = tracker();
		assertNull(restarted.observe(o(0, GrandExchangeOfferState.BOUGHT, 10), T + 3));
		assertEquals(1, restarted.state().pending.size());
	}

	@Test public void initialTerminalOfferIsBaselineOnly() throws Exception
	{
		OfferTracker tracker = tracker();
		assertNull(tracker.observe(o(0, GrandExchangeOfferState.BOUGHT, 10), T));
		assertTrue(tracker.state().pending.isEmpty());
	}

	@Test public void slotReuseProducesNewIdentity() throws Exception
	{
		OfferTracker tracker = tracker();
		tracker.observe(o(0, GrandExchangeOfferState.BUYING, 0), T);
		String first = tracker.observe(o(0, GrandExchangeOfferState.BOUGHT, 10), T + 1000).event_id;
		tracker.delivered(first);
		tracker.observe(o(0, GrandExchangeOfferState.BUYING, 0), T + 2000);
		String second = tracker.observe(o(0, GrandExchangeOfferState.BOUGHT, 10), T + 3000).event_id;
		assertNotEquals(first, second);
	}

	@Test public void sameItemOffersInDifferentSlotsHaveDifferentIds() throws Exception
	{
		OfferTracker tracker = tracker();
		tracker.observe(o(0, GrandExchangeOfferState.BUYING, 0), T);
		tracker.observe(o(1, GrandExchangeOfferState.BUYING, 0), T);
		assertNotEquals(tracker.observe(o(0, GrandExchangeOfferState.BOUGHT, 10), T + 1).event_id,
			tracker.observe(o(1, GrandExchangeOfferState.BOUGHT, 10), T + 1).event_id);
	}

	@Test public void eventIdIsDeterministicAcrossReload() throws Exception
	{
		OfferTracker tracker = tracker();
		tracker.observe(o(2, GrandExchangeOfferState.SELLING, 0), T);
		TransactionEvent event = tracker.observe(o(2, GrandExchangeOfferState.SOLD, 10), T + 1000);
		assertEquals(event.event_id, tracker().state().pending.get(0).event.event_id);
		assertEquals(64, event.event_id.length());
	}

	@Test public void totalUsesLongAndOverflowIsRejected()
	{
		assertEquals(4_000_000_000L, OfferTracker.safeTotal(2_000_000_000L, 2));
		try { OfferTracker.safeTotal(Long.MAX_VALUE, 2); fail(); }
		catch (ArithmeticException expected) { }
	}

	@Test public void queuePersistsAndReloads() throws Exception
	{
		OfferTracker tracker = tracker();
		tracker.observe(o(0, GrandExchangeOfferState.BUYING, 1), T);
		tracker.observe(o(0, GrandExchangeOfferState.BOUGHT, 10), T + 1);
		assertEquals(1, tracker().state().pending.size());
	}

	private void assertEvent(GrandExchangeOfferState start, GrandExchangeOfferState end, String side, String state) throws Exception
	{
		OfferTracker tracker = tracker();
		tracker.observe(o(0, start, 2), T);
		TransactionEvent event = tracker.observe(o(0, end, 10), T + 30_000);
		assertNotNull(event);
		assertEquals(side, event.side);
		assertEquals(state, event.offer_state);
		assertEquals(10, event.quantity);
		assertEquals(1500L, event.total_coins);
		assertEquals("Coal", event.item_name);
		assertEquals(T / 1000, event.observed_at);
	}

	private OfferTracker tracker() throws IOException { return new OfferTracker(store, id -> "Coal"); }
	private OfferSnapshot o(int slot, GrandExchangeOfferState state, int quantity)
	{
		return new OfferSnapshot(slot, state, 453, quantity, 10, 150);
	}

	private static final class MemoryStore implements StateStore
	{
		private final Gson gson = new Gson();
		private String json;
		@Override public PersistedState load() { return json == null ? new PersistedState() : gson.fromJson(json, PersistedState.class); }
		@Override public void save(PersistedState state) { json = gson.toJson(state); }
	}
}
