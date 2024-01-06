package com.hanghae.lemonairstreaming.rmtp.model;

import java.util.concurrent.ConcurrentHashMap;

public class StreamContext {

	public final ConcurrentHashMap<String, Stream> context;

	public StreamContext() {
		this.context = new ConcurrentHashMap<>();
	}

	public void addStream(Stream stream) {
		context.put(stream.getStreamerId(), stream);
	}

	public void deleteStream(String streamName) {
		context.remove(streamName);
	}

	public Stream getStream(String streamName) {
		return (streamName != null) ? context.getOrDefault(streamName, null) : null;
	}
}