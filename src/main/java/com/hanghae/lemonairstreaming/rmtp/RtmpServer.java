package com.hanghae.lemonairstreaming.rmtp;

import static com.hanghae.lemonairstreaming.util.ThreadSchedulers.*;

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
				.flatMap(stream -> webClient.post()
					.uri(serviceServerHost + "/api/streams/" + stream.getStreamerId() + "/check")
					.body(Mono.just(new StreamKey(stream.getStreamKey())), StreamKey.class)
					.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
					.retrieve()
					.bodyToMono(Boolean.class)
					.log("service서버에 스트림키 검증")
					.retryWhen(Retry.fixedDelay(3, Duration.ofMillis(500)))
					.subscribeOn(IO.scheduler())
					.doOnError(error -> log.info(error.getMessage()))
					.onErrorReturn(Boolean.FALSE)
					.filter(isStreamKeyValid -> isStreamKeyValid)
					.doOnSuccess((valid) -> stream.sendPublishMessage())
					.then(Mono.fromFuture(requestTranscoding(stream))))
				.then())
			.bindNow();
		server.onDispose().block();
	}

	private CompletableFuture<Void> requestTranscoding(Stream stream) {
		return stream.getReadyToBroadcast().thenRun(() -> {
			webClient.get()
				.uri(transcodingServerIp + ":" + transcodingServerPort + "/transcode/" + stream.getStreamerId())
				.retrieve()
				.bodyToMono(Long.class)
				.retryWhen(Retry.fixedDelay(3, Duration.ofMillis(1000)))
				.doOnError(error -> log.error("Transcoding 서버 연결 오류 " + error.getMessage()))
				.onErrorComplete()
				.log("Transcoding 서버에 영상 변환 요청")
				.subscribeOn(IO.scheduler())
				.then(sendStreamingIsReadyToServiceServer(stream))
				.subscribe();
		});

	}

	private Mono<Void> sendStreamingIsReadyToServiceServer(Stream stream) {
		return Mono.defer(() -> webClient.post()
			.uri(serviceServerHost + "/api/streams/" + stream.getStreamerId() + "/onair")
			.retrieve()
			.bodyToMono(Boolean.class)
			.retryWhen(Retry.fixedDelay(3, Duration.ofMillis(500)))
			.doOnError(e -> log.error(e.getMessage()))
			.onErrorReturn(Boolean.FALSE)
			.log("service 서버에 방송 시작 준비 완료 알림")
			.subscribeOn(IO.scheduler())
			.doOnSuccess(t -> log.info(stream.getStreamerId() + (t ? "방송이 시작되었습니다." : "방송이 시작되지 못했습니다.")))
			.then());
	}
}