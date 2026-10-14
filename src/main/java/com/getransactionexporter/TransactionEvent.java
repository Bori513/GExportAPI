package com.getransactionexporter;

final class TransactionEvent
{
	final int schema_version = 1;
	String event_id;
	int offer_slot;
	String side;
	int item_id;
	String item_name;
	int quantity;
	long unit_price;
	long total_coins;
	String offer_state;
	long observed_at;
	long completed_at;
}
