package com.hanghae.lemonairstreaming.rmtp;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import reactor.core.publisher.Flux;
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

	// Spring의 WebClient를 주입합니다.
	@Autowired
	private WebClient webClient;
	// MSA 관련 설정
	//transcoding.server 프로퍼티 값을 주입합니다. 트랜스코딩 서버 주소를 나타냅니다.
	@Value("${external.transcoding.server.ip}")
	private String transcodingServerIp;

	@Value("${external.transcoding.server.port}")
	private int transcodingServerPort;
	//auth.server 프로퍼티 값을 주입합니다. 인증 서버 주소를 나타냅니다.
	@Value("${external.service.server.ip}")
	private String serviceServerIp;

//	@Value("${external.auth.server.ip}")
//	private String serviceServerIp;

	@Value("${internal.rtmp.server.port}")
	private int rtmpPort;

	protected abstract RtmpMessageHandler getRtmpMessageHandler();

	protected abstract InboundConnectionLogger getInboundConnectionLogger();

	protected abstract HandshakeHandler getHandshakeHandler();

	protected abstract ChunkDecoder getChunkDecoder();

	protected abstract ChunkEncoder getChunkEncoder();

	@Override
	public void run(String... args) {
		runWithOutAuthTranscodingException(args);
	}

	// auth, transcoding 서버 통신간 예외처리 등 복잡한 로직 제외한 서버 build 코드
	private void runWithOutAuthTranscodingException(String... args) {
		DisposableServer server = TcpServer.create()
			.port(rtmpPort)
			.doOnBound(disposableServer ->
				log.info("RTMP 서버가 포트 {} 에서 시작됩니다.", disposableServer.port()))
			.doOnConnection(connection -> connection
				.addHandlerLast(getInboundConnectionLogger())
				.addHandlerLast(getHandshakeHandler())
				.addHandlerLast(getChunkDecoder())
				.addHandlerLast(getChunkEncoder())
				.addHandlerLast(getRtmpMessageHandler()))
			.option(ChannelOption.SO_BACKLOG, 128)
			.childOption(ChannelOption.SO_KEEPALIVE, true)
			.handle((in, out) -> in
				.receiveObject()
				.cast(Stream.class)
					.flatMap(stream -> {
							log.info("스트리머 {} 의 stream key가 유효합니다.", stream.getStreamName());
							stream.sendPublishMessage();
							stream.getReadyToBroadcast().thenRun(() -> webClient
								.get()
								//문제 발생 예상 코드 아래로 접속 요청을 보내는데, 아래 .retryWhen에서 정의한 3번의 재시도가 모두 실패했다는 Retries wchausted: 3/3 오류가 발생한다.
								.uri(transcodingServerIp + ":" + transcodingServerPort + "/transcode/" + stream.getStreamName())
								.retrieve()
								.bodyToMono(Long.class)
								.retryWhen(Retry.fixedDelay(3, Duration.ofMillis(1000)))
								.doOnError(error -> log.info("Transcoding 서버에서 다음의 에러가 발생했습니다 : " + error.getMessage()))
								.onErrorComplete()
								.subscribe((s) -> {
									log.info("Transcoding server started ffmpeg with pid " + s.toString());
									webClient
										.post()
										.uri(serviceServerIp + "/api/streams/" + stream.getStreamName() + "/streaming")
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
												log.info("Service 서버와 통신 에러 발생");
											}
										});
								}));
						return Mono.empty();
						}).then()).bindNow();
		server.onDispose().block();
	}

	// rtmp 서버 원본
	public void runWithAuthAndTrancodingException(String... args) {
		DisposableServer server = TcpServer.create()
			.port(1935)
			.doOnBound(disposableServer ->
				log.info("RTMP 서버가 포트 {} 에서 시작됩니다.", disposableServer.port()))
			.doOnConnection(connection -> connection
				.addHandlerLast(getInboundConnectionLogger())
				.addHandlerLast(getHandshakeHandler())
				.addHandlerLast(getChunkDecoder())
				.addHandlerLast(getChunkEncoder())
				.addHandlerLast(getRtmpMessageHandler()))
			.option(ChannelOption.SO_BACKLOG, 128)
			.childOption(ChannelOption.SO_KEEPALIVE, true)
			.handle((in, out) -> in
				.receiveObject()
				.cast(Stream.class)
				.flatMap(stream -> {
					return webClient
						.post()
						.uri(serviceServerIp + "/api/streams/" + stream.getStreamName() + "/check")
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
									.uri(transcodingServerIp + ":" + transcodingServerPort + "/transcode/" + stream.getStreamName())
									.retrieve()
									.bodyToMono(Long.class)
									.retryWhen(Retry.fixedDelay(3, Duration.ofMillis(1000)))
									.doOnError(error -> {
										log.info("Transcoding 서버에서 다음의 에러가 발생했습니다 : " + error.getMessage());
										webClient
											.delete()
											.uri(serviceServerIp + "/api/streams/" + stream.getStreamName() + "/streaming")
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
											.uri(serviceServerIp + "/api/streams/" + stream.getStreamName() + "/streaming")
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
													log.info("Service 서버와 통신 에러 발생");
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