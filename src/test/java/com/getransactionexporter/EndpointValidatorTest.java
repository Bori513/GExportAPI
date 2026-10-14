package com.getransactionexporter;

import org.junit.Test;
import static org.junit.Assert.*;

public class EndpointValidatorTest
{
	@Test public void acceptsHttpsAndLoopbackHttp()
	{
		assertNotNull(EndpointValidator.validate("https://example.com/events"));
		assertNotNull(EndpointValidator.validate("http://localhost:8765/events"));
		assertNotNull(EndpointValidator.validate("http://127.0.0.1/events"));
		assertNotNull(EndpointValidator.validate("http://[::1]/events"));
	}

	@Test public void rejectsRemoteHttpMalformedAndUnsupported()
	{
		assertNull(EndpointValidator.validate("http://example.com/events"));
		assertNull(EndpointValidator.validate("not a url"));
		assertNull(EndpointValidator.validate("ftp://localhost/file"));
		assertNull(EndpointValidator.validate("https://user:secret@example.com/events"));
	}
}
