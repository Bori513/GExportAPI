package com.getransactionexporter;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import net.runelite.client.util.Filepath;

final class FileStateStore implements StateStore
{
	private static final long MAX_FILE_BYTES = 2L * 1024L * 1024L;
	private final Gson gson;
	private final Filepath file;
	private final Filepath temp;

	FileStateStore(Gson gson, Filepath directory) throws IOException
	{
		this.gson = gson;
		directory.createDirectories();
		this.file = directory.joinSegment("state.json");
		this.temp = directory.joinSegment("state.tmp");
	}

	@Override
	public PersistedState load() throws IOException
	{
		if (!file.exists()) return new PersistedState();
		try
		{
			if (file.size() > MAX_FILE_BYTES) throw new IOException("state file exceeds safe size limit");
			try (Reader reader = file.openReader())
			{
				PersistedState state = gson.fromJson(reader, PersistedState.class);
				if (state == null || state.version != 1 || state.offers == null || state.pending == null || state.delivered == null)
					throw new IOException("invalid state structure");
				return state;
			}
		}
		catch (JsonParseException | IllegalStateException ex)
		{
			quarantine();
			return new PersistedState();
		}
		catch (IOException ex)
		{
			quarantine();
			return new PersistedState();
		}
	}

	private void quarantine() throws IOException
	{
		Filepath corrupt = file.getParent().joinSegment("state.corrupt.json");
		corrupt.deleteIfExists();
		file.moveTo(corrupt, StandardCopyOption.REPLACE_EXISTING);
	}

	@Override
	public void save(PersistedState state) throws IOException
	{
		String json = gson.toJson(state);
		if (json.length() > MAX_FILE_BYTES) throw new IOException("state exceeds safe size limit");
		temp.write(json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
		try
		{
			temp.moveTo(file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException ex)
		{
			temp.moveTo(file, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
