package com.hanghae.lemonairstreaming.Handler;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.web.reactive.function.client.WebClient;

import com.hanghae.lemonairstreaming.rmtp.RtmpServer;
import com.hanghae.lemonairstreaming.rmtp.model.StreamContext;

import lombok.NoArgsConstructor;

@Configuration
@NoArgsConstructor
public class HandlerConfig {

	@Bean
	public WebClient getWebClient() {
		return WebClient.create();
	}

	@Bean
	public StreamContext streamContext() {
		return new StreamContext();
	}

	@Bean
	@Scope(value = "prototype")
	public ChunkDecoder chunkDecoder() {
		return new ChunkDecoder();
	}

	@Bean
	@Scope(value = "prototype")
	public ChunkEncoder chunkEncoder() {
		return new ChunkEncoder();
	}

	@Bean
	@Scope(value = "prototype")
	public HandshakeHandler handshakeHandler() {
		return new HandshakeHandler();
	}

	@Bean
	@Scope(value = "prototype")
	public InboundConnectionLogger inboundConnectionLogger() {
		return new InboundConnectionLogger();
	}

	@Bean
	@Scope(value = "prototype")
	public RtmpMessageHandler rtmpMessageHandler() {
		return new RtmpMessageHandler(streamContext());
	}

	@Bean
	public RtmpServer rtmpServer() {
		return new RtmpServer() {
			@Override
			protected RtmpMessageHandler getRtmpMessageHandler() {
				return rtmpMessageHandler();
			}

			@Override
			protected InboundConnectionLogger getInboundConnectionLogger() {
				return inboundConnectionLogger();
			}

			@Override
			protected HandshakeHandler getHandshakeHandler() {
				return handshakeHandler();
			}

			@Override
			protected ChunkDecoder getChunkDecoder() {
				return chunkDecoder();
			}

			@Override
			protected ChunkEncoder getChunkEncoder() {
				return chunkEncoder();
			}
		};
	}
}