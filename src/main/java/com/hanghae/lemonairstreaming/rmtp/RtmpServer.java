package com.hanghae.lemonairstreaming.rmtp;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

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

	@Autowired
	private WebClient webClient;

	@Value("${external.transcoding.server.ip}")
	private String transcodingServerIp;

	@Value("${external.transcoding.server.port}")
	private int transcodingServerPort;

	@Value("${external.service.server.host}")
	private String serviceServerHost;

	@Value("${external.chat.server.host}")
	private String chatServerHost;

	@Value("${internal.rtmp.server.port}")
	private int rtmpPort;

	protected abstract RtmpMessageHandler getRtmpMessageHandler();

	protected abstract InboundConnectionLogger getInboundConnectionLogger();

	protected abstract HandshakeHandler getHandshakeHandler();

	protected abstract ChunkDecoder getChunkDecoder();

	protected abstract ChunkEncoder getChunkEncoder();

	@Override
	public void run(String... args) {
		runWithExtractedMethod(args);
	}

	private void runWithExtractedMethod(String... args) {

		DisposableServer server = TcpServer.create()
			.port(rtmpPort)
			.doOnBound(disposableServer -> log.info("tcp server created"))
			.doOnConnection(connection -> connection.addHandlerLast(getInboundConnectionLogger())
				.addHandlerLast(getHandshakeHandler())
				.addHandlerLast(getChunkDecoder())
				.addHandlerLast(getChunkEncoder())
				.addHandlerLast(getRtmpMessageHandler()))
			.option(ChannelOption.SO_BACKLOG, 128)
			.childOption(ChannelOption.SO_KEEPALIVE, true)
			.handle((in, out) -> in.receiveObject()
				.cast(Stream.class)
				.doOnError((e) -> log.error("Stream class 로 캐스팅 실패, 지원하지 않는 방송 송출 프로그램이거나 잘못된 요청"))
				.onErrorComplete()
				.flatMap(stream -> {
					return webClient.post()
						.uri(serviceServerHost + "/api/streams/" + stream.getStreamerId() + "/check")
						.body(Mono.just(new StreamKey(stream.getStreamKey())), StreamKey.class)
						.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
						.retrieve()
						.bodyToMono(Boolean.class)
						.log()
						.retryWhen(Retry.fixedDelay(3, Duration.ofMillis(500)))
						.doOnError(error -> log.info(error.getMessage()))
						.onErrorReturn(Boolean.FALSE)
						.filter(isStreamKeyValid -> isStreamKeyValid)
						.flatMap(isStreamKeyValid -> {
							log.info("스트리머: {} 스트림 키 검증 완료", stream.getStreamerId());
							stream.sendPublishMessage();
							requestTranscoding(stream);
							return Mono.empty();
						});
				})
				.then())
			.bindNow();
		server.onDispose().block();
	}

	private CompletableFuture<Void> requestTranscoding(Stream stream) {
		return stream.getReadyToBroadcast().thenRun(() -> {
			log.info("트랜스코딩 서버 ip, port {},{}", transcodingServerIp, transcodingServerPort);
			webClient.get()
				.uri(transcodingServerIp + ":" + transcodingServerPort + "/transcode/" + stream.getStreamerId())
				.retrieve()
				.bodyToMono(Long.class)
				.retryWhen(Retry.fixedDelay(3, Duration.ofMillis(1000)))
				.doOnError(error -> log.info("Transcoding 서버 연결 오류 " + error.getMessage()))
				.onErrorComplete()
				.subscribe((ffmpegProcessPid) -> createChattingRoom(stream));
		});
	}

	private void createChattingRoom(Stream stream) {
		webClient.post()
			.uri(chatServerHost + "/chat/room/" + stream.getStreamerId())
			.retrieve()
			.bodyToMono(Boolean.class)
			.log()
			.retryWhen(Retry.fixedDelay(3, Duration.ofMillis(500)))
			.doOnError(e -> log.info(e.getMessage()))
			.onErrorReturn(Boolean.FALSE)
			.subscribeOn(Schedulers.parallel())
			.onErrorComplete()
			.subscribe(success -> {
				if (success) {
					log.info("채팅 서버에 채팅방 개설 완료");
					sendStreamingIsReadyToServiceServer(stream);
				}
			});
	}

	private void sendStreamingIsReadyToServiceServer(Stream stream) {
		webClient.post()
			.uri(serviceServerHost + "/api/streams/" + stream.getStreamerId() + "/onair")
			.retrieve()
			.bodyToMono(Boolean.class)
			.log()
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
	}
}