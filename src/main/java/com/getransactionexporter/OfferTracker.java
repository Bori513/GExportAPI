package com.getransactionexporter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.runelite.api.GrandExchangeOfferState;

final class OfferTracker
{
	static final int MAX_PENDING = 256;
	static final int MAX_DELIVERED = 4096;
	private final StateStore store;
	private final PersistedState state;

	OfferTracker(StateStore store) throws IOException
	{
		this.store = store;
		this.state = store.load();
	}

	synchronized TransactionEvent observe(OfferSnapshot offer, long nowMillis) throws IOException
	{
		if (offer.slot < 0 || offer.slot > 7) return null;
		PersistedState.TrackedOffer tracked = state.offers.get(offer.slot);
		if (offer.state == GrandExchangeOfferState.EMPTY)
		{
			if (tracked != null)
			{
				state.offers.remove(offer.slot);
				store.save(state);
			}
			return null;
		}

		boolean active = offer.state == GrandExchangeOfferState.BUYING || offer.state == GrandExchangeOfferState.SELLING;
		boolean sameLifecycle = tracked != null && tracked.itemId == offer.itemId
			&& tracked.totalQuantity == offer.totalQuantity && tracked.price == offer.price;
		if (sameLifecycle && active && isTerminalName(tracked.lastState)) sameLifecycle = false;
		if (!sameLifecycle)
		{
			tracked = new PersistedState.TrackedOffer();
			tracked.itemId = offer.itemId;
			tracked.totalQuantity = offer.totalQuantity;
			tracked.price = offer.price;
			tracked.side = side(offer.state);
			tracked.observedAt = nowMillis / 1000L;
			tracked.generation = state.nextGeneration++;
			tracked.armed = active;
			tracked.lastState = offer.state.name();
			tracked.lastQuantity = offer.quantity;
			state.offers.put(offer.slot, tracked);
			store.save(state);
			return null; // baseline: terminal offers first seen here are deliberately ignored
		}

		if (active) tracked.armed = true;
		boolean terminal = isTerminal(offer.state);
		if (!terminal || !tracked.armed || offer.quantity <= 0)
		{
			if (!offer.state.name().equals(tracked.lastState) || offer.quantity != tracked.lastQuantity)
			{
				tracked.lastState = offer.state.name();
				tracked.lastQuantity = offer.quantity;
				store.save(state);
			}
			return null;
		}

		TransactionEvent event = event(offer, tracked, nowMillis);
		if (state.delivered.contains(event.event_id) || findPending(event.event_id) != null) return null;
		if (state.pending.size() >= MAX_PENDING) throw new IOException("outbound queue is full; transaction retained for a later repeated event");
		PersistedState.QueuedEvent queued = new PersistedState.QueuedEvent();
		queued.event = event;
		state.pending.add(queued);
		tracked.armed = false;
		tracked.lastState = offer.state.name();
		tracked.lastQuantity = offer.quantity;
		store.save(state); // durable before the caller may deliver
		return event;
	}

	synchronized PersistedState state() { return state; }
	synchronized void save() throws IOException { store.save(state); }
	synchronized List<PersistedState.QueuedEvent> pending() { return new ArrayList<>(state.pending); }

	synchronized void markPermanent(PersistedState.QueuedEvent queued) throws IOException
	{
		queued.permanentFailure = true;
		store.save(state);
	}

	synchronized void markTemporary(PersistedState.QueuedEvent queued, long nextAttemptAt) throws IOException
	{
		queued.attempts++;
		queued.nextAttemptAt = nextAttemptAt;
		store.save(state);
	}

	synchronized void delivered(String id) throws IOException
	{
		state.pending.removeIf(q -> id.equals(q.event.event_id));
		state.delivered.add(id);
		while (state.delivered.size() > MAX_DELIVERED)
		{
			Iterator<String> iterator = state.delivered.iterator();
			iterator.next();
			iterator.remove();
		}
		store.save(state);
	}

	private PersistedState.QueuedEvent findPending(String id)
	{
		for (PersistedState.QueuedEvent q : state.pending) if (id.equals(q.event.event_id)) return q;
		return null;
	}

	private TransactionEvent event(OfferSnapshot offer, PersistedState.TrackedOffer tracked, long nowMillis)
	{
		TransactionEvent event = new TransactionEvent();
		event.offer_slot = offer.slot;
		event.side = tracked.side;
		event.item_id = offer.itemId;
		event.item_name = offer.itemName == null || offer.itemName.isEmpty() ? "Unknown" : offer.itemName;
		event.quantity = offer.quantity;
		event.unit_price = offer.price;
		event.total_coins = safeTotal(offer.quantity, offer.price);
		event.offer_state = offer.state == GrandExchangeOfferState.BOUGHT || offer.state == GrandExchangeOfferState.SOLD
			? "COMPLETED" : "CANCELLED_PARTIAL";
		event.observed_at = tracked.observedAt;
		event.completed_at = nowMillis / 1000L;
		String identity = "1|" + tracked.generation + "|" + offer.slot + "|" + event.side + "|" + offer.itemId
			+ "|" + offer.totalQuantity + "|" + offer.price + "|" + offer.quantity + "|" + event.offer_state + "|" + tracked.observedAt;
		event.event_id = sha256(identity);
		return event;
	}

	static long safeTotal(long quantity, long price)
	{
		if (quantity < 0 || price < 0) throw new IllegalArgumentException("negative GE value");
		return Math.multiplyExact(quantity, price);
	}

	private static boolean isTerminal(GrandExchangeOfferState state)
	{
		return state == GrandExchangeOfferState.BOUGHT || state == GrandExchangeOfferState.SOLD
			|| state == GrandExchangeOfferState.CANCELLED_BUY || state == GrandExchangeOfferState.CANCELLED_SELL;
	}

	private static boolean isTerminalName(String state)
	{
		return "BOUGHT".equals(state) || "SOLD".equals(state)
			|| "CANCELLED_BUY".equals(state) || "CANCELLED_SELL".equals(state);
	}

	private static String side(GrandExchangeOfferState state)
	{
		return state == GrandExchangeOfferState.BUYING || state == GrandExchangeOfferState.BOUGHT
			|| state == GrandExchangeOfferState.CANCELLED_BUY ? "BUY" : "SELL";
	}

	private static String sha256(String value)
	{
		try
		{
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder out = new StringBuilder(64);
			for (byte b : hash) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
			return out.toString();
		}
		catch (NoSuchAlgorithmException ex)
		{
			throw new IllegalStateException("SHA-256 unavailable", ex);
		}
	}
}
