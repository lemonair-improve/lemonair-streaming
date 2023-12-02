package com.hanghae.lemonairstreaming.config;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.rsocket.RSocketRequester;
import org.springframework.messaging.rsocket.RSocketStrategies;
import org.springframework.util.MimeTypeUtils;

import reactor.util.retry.Retry;

@Configuration
public class RsocketConfig {

	//@Value("${ec2.server}")
	private String host = "127.0.0.1";

	@Bean
	public RSocketRequester getRSocketRequester(RSocketStrategies rSocketStrategies) {
		return RSocketRequester.builder()
			.rsocketConnector(connector -> connector.reconnect(Retry.backoff(10, Duration.ofMillis(500))))
			.rsocketStrategies(rSocketStrategies)
			.dataMimeType(MimeTypeUtils.APPLICATION_JSON)
			.tcp(host, 6565);
	}
}