package com.github.andirady.mq;


import java.net.URI;
import java.net.URISyntaxException;
import java.util.StringJoiner;

public record QuerySpec(String groupId, String artifactId, String version) {

	static QuerySpec of(String spec) {
		var parts = spec.split(":");
		switch (parts.length) {
			case 1 :
				return new QuerySpec(null, parts[0], null);
			case 2 :
				return new QuerySpec(parts[0], parts[1], null);
			case 3 :
				return new QuerySpec(parts[0], parts[1], parts[2]);
			default :
				throw new IllegalArgumentException("Invalid spec: " + spec);
		}
	}

	public URI toURI() {
		try {
			return new URI("https", "search.maven.org", "/solrsearch/select", "q=" + toString() + "&start=0&rows=1",
					null);
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException(e);
		}
	}

	public String toString() {
		var parts = new StringJoiner(" AND ");
		if (groupId != null) {
			parts.add("g:" + groupId);
		}

		if (artifactId != null) {
			parts.add("a:" + artifactId);
		}

		if (version != null) {
			parts.add("v:" + version);
		}

		return parts.toString();
	}
}
