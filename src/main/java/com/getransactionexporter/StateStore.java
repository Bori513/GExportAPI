package com.getransactionexporter;

import java.io.IOException;

interface StateStore
{
	PersistedState load() throws IOException;
	void save(PersistedState state) throws IOException;
}
