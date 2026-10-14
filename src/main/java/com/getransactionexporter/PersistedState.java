package com.getransactionexporter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class PersistedState
{
	int version = 1;
	long nextGeneration = 1;
	Map<Integer, TrackedOffer> offers = new LinkedHashMap<>();
	List<QueuedEvent> pending = new ArrayList<>();
	Set<String> delivered = new LinkedHashSet<>();

	static final class TrackedOffer
	{
		int itemId;
		int totalQuantity;
		long price;
		String side;
		long observedAt;
		long generation;
		boolean armed;
		String lastState;
		int lastQuantity;
	}

	static final class QueuedEvent
	{
		TransactionEvent event;
		int attempts;
		long nextAttemptAt;
		boolean permanentFailure;
	}
}
