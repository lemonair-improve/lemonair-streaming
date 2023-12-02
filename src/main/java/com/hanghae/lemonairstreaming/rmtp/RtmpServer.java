package com.hanghae.lemonairstreaming.rmtp;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import com.hanghae.lemonairstreaming.Handler.ChunkDecoder;
import com.hanghae.lemonairstreaming.Handler.ChunkEncoder;
import com.hanghae.lemonairstreaming.Handler.HandshakeHandler;
import com.hanghae.lemonairstreaming.Handler.InboundConnectionLogger;
import com.hanghae.lemonairstreaming.Handler.RtmpMessageHandler;
import com.hanghae.lemonairstreaming.rmtp.entity.StreamKey;
import com.hanghae.lemonairstreaming.rmtp.model.Stream;

import io.netty.channel.ChannelOption;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.DisposableServer;
import reactor.netty.tcp.TcpServer;
import reactor.util.retry.Retry;

@NoArgsConstructor
@Getter
@Setter
@Slf4j
public abstract class RtmpServer implements CommandLineRunner {

	protected abstract RtmpMessageHandler getRtmpMessageHandler();

	protected abstract InboundConnectionLogger getInboundConnectionLogger();

	protected abstract HandshakeHandler getHandshakeHandler();

	protected abstract ChunkDecoder getChunkDecoder();

	protected abstract ChunkEncoder getChunkEncoder();

	// Spring의 WebClient를 주입합니다.
	@Autowired
	private WebClient webClient;

	// MSA 관련 설정
	//transcoding.server 프로퍼티 값을 주입합니다. 트랜스코딩 서버 주소를 나타냅니다.
	//@Value("${transcoding.server}")
	private String transcodingAddress = "127.0.0.1";

	//auth.server 프로퍼티 값을 주입합니다. 인증 서버 주소를 나타냅니다.
	//@Value("${auth.server}")
	private String authAddress = "127.0.0.1";

	@Override
	public void run(String... args) {
		DisposableServer server = TcpServer.create()
			.port(1935)
			.doOnBound(disposableServer ->
				log.info("RTMP 서버가 포트 {} 에서 시작됩니다.", disposableServer.port()))
			// 연결이후 서버 구동에 필요한 5개의 핸들러를 추가
			.doOnConnection(connection -> connection
				.addHandlerLast(getInboundConnectionLogger())
				.addHandlerLast(getHandshakeHandler())
				.addHandlerLast(getChunkDecoder())
				.addHandlerLast(getChunkEncoder())
				.addHandlerLast(getRtmpMessageHandler()))
			// 서버와 클라이언트 간의 TCP 소켓 옵션
			//SO_BACKLOG은 서버 소켓이 수용할 수 있는 최대 연결 대기 큐 크기를 설정합니다.
			// 이 값은 클라이언트로부터의 연결 요청이 들어오면, 서버가 아직 처리하지 않은 연결 대기열에 대기하게 됩니다.
			// 128은 일반적으로 사용되는 값 중 하나로, 서버가 동시에 처리 가능한 최대 연결 수를 나타냅니다.
			.option(ChannelOption.SO_BACKLOG, 128)
			//SO_KEEPALIVE는 TCP 연결이 유휴 상태인 경우 해당 연결을 유지할지 여부를 결정하는 옵션입니다.
			// true로 설정하면 TCP 연결이 일정 시간 동안 데이터를 주고받지 않으면 자동으로 연결을 유지합니다.
			// 이는 네트워크의 불안정성으로 인해 연결이 끊어진 경우에 해당 연결을 감지하고 다시 연결을 시도하는 데 도움이 됩니다.
			.childOption(ChannelOption.SO_KEEPALIVE, true)
			.handle((in, out) -> in
				.receiveObject()
				.cast(Stream.class)
				.flatMap(stream -> {
					return webClient
						.post()
						.uri(authAddress + "/broadcasts/" + stream.getStreamName() + "/check")
						.body(Mono.just(new StreamKey(stream.getStreamKey())), StreamKey.class)
						.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
						.retrieve()
						.bodyToMono(Boolean.class).log()
						.retryWhen(Retry.fixedDelay(3, Duration.ofMillis(500)))
						.doOnError(error -> log.info(error.getMessage()))
						.onErrorReturn(Boolean.FALSE)
						.flatMap(ans -> {
							if (ans) {
								log.info("스트리머 {} 의 stream key가 유효합니다.", stream.getStreamName());
								stream.sendPublishMessage();
								stream.getReadyToBroadcast().thenRun(() -> webClient
									.get()
									.uri(transcodingAddress + "/ffmpeg/" + stream.getStreamName())
									.retrieve()
									.bodyToMono(Long.class)
									.retryWhen(Retry.fixedDelay(3, Duration.ofMillis(1000)))
									.doOnError(error -> {
										log.info("Transcoding 서버에서 다음의 에러가 발생했습니다 : " + error.getMessage());
										webClient
											.post()
											.uri(authAddress + "/broadcasts/" + stream.getStreamName() + "/offair")
											.retrieve()
											.bodyToMono(Boolean.class)
											.retryWhen(Retry.fixedDelay(3, Duration.ofMillis(500)))
											.doOnError(e -> log.info(e.getMessage()))
											.onErrorReturn(Boolean.FALSE)
											.subscribeOn(Schedulers.parallel())
											.subscribe((s) -> {
												log.info("방송 송출이 끊어집니다.");
												if (s) {
													log.info("방송이 종료됩니다.");
												} else {
													log.info("ContentService 서버와 통신 에러 발생");
												}
											});
										stream.closeStream();
										stream.getPublisher().disconnect();
									})
									.onErrorComplete()
									.subscribe((s) -> {
										log.info("Transcoding server started ffmpeg with pid " + s.toString());
										webClient
											.post()
											.uri(authAddress + "/broadcasts/" + stream.getStreamName() + "/onair")
											.retrieve()
											.bodyToMono(Boolean.class)
											.retryWhen(Retry.fixedDelay(3, Duration.ofMillis(500)))
											.doOnError(e -> log.info(e.getMessage()))
											.onErrorReturn(Boolean.FALSE)
											.subscribeOn(Schedulers.parallel())
											.subscribe((t) -> {
												if (t) {
													log.info("방송이 시작됩니다.");
												} else {
													log.info("ContentService 서버와 통신 에러 발생");
												}
											});
									}));
							} else {
								stream.getPublisher().disconnect();
							}
							return Mono.empty();
						});
				})
				.then())
			.bindNow();
		server.onDispose().block();
	}
}