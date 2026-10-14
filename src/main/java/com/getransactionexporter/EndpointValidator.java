package com.getransactionexporter;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import okhttp3.HttpUrl;

final class EndpointValidator
{
	private EndpointValidator() { }

	static HttpUrl validate(String value)
	{
		if (value == null) return null;
		try
		{
			URI uri = new URI(value.trim());
			String scheme = uri.getScheme();
			String host = uri.getHost();
			if (scheme == null || host == null || uri.getUserInfo() != null || uri.getFragment() != null)
				return null;
			scheme = scheme.toLowerCase(Locale.ROOT);
			if (!"https".equals(scheme) && !("http".equals(scheme) && isLoopback(host))) return null;
			return HttpUrl.parse(uri.toASCIIString());
		}
		catch (URISyntaxException | IllegalArgumentException ex)
		{
			return null;
		}
	}

	private static boolean isLoopback(String host)
	{
		String normalized = host.toLowerCase(Locale.ROOT);
		if (normalized.startsWith("[") && normalized.endsWith("]"))
			normalized = normalized.substring(1, normalized.length() - 1);
		return "localhost".equals(normalized) || "127.0.0.1".equals(normalized) || "::1".equals(normalized)
			|| "0:0:0:0:0:0:0:1".equals(normalized);
	}
}
