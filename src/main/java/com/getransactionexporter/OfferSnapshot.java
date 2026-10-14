package com.getransactionexporter;

import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;

final class OfferSnapshot
{
	final int slot;
	final GrandExchangeOfferState state;
	final int itemId;
	final int quantity;
	final int totalQuantity;
	final long price;
	final long spent;
	final String itemName;

	OfferSnapshot(int slot, GrandExchangeOfferState state, int itemId, int quantity, int totalQuantity, long price,
		long spent, String itemName)
	{
		this.slot = slot;
		this.state = state;
		this.itemId = itemId;
		this.quantity = quantity;
		this.totalQuantity = totalQuantity;
		this.price = price;
		this.spent = spent;
		this.itemName = itemName;
	}

	static OfferSnapshot from(int slot, GrandExchangeOffer offer, String itemName)
	{
		return new OfferSnapshot(slot, offer.getState(), offer.getItemId(), offer.getQuantitySold(),
			offer.getTotalQuantity(), offer.getPrice(), offer.getSpent(), itemName);
	}
}
