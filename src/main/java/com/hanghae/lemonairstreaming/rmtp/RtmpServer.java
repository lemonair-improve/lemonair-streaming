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
	// WebFlux에서 제공하는 비동기 HTTP 클라이언트
	@Autowired
	private WebClient webClient;
	// MSA 관련 설정
	//transcoding.server 프로퍼티 값을 주입합니다. 트랜스코딩 서버 주소를 나타냅니다.
	@Value("${external.transcoding.server.ip}")
	private String transcodingServerIp;

	@Value("${external.transcoding.server.port}")
	private int transcodingServerPort;
	//auth.server 프로퍼티 값을 주입합니다. 인증 서버 주소를 나타냅니다.
	@Value("${external.contents.server.ip}")
	private String contentsServerIp;

	@Value("${external.auth.server.ip}")
	private String authServerIp;

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
		DisposableServer server = TcpServer.create() // TCP 서버 생성
			.port(rtmpPort) // RTMP 포트 설정 (1935)
			.doOnBound(disposableServer -> // 서버가 바인드 되면 콜백 (시작 로깅)
				log.info("RTMP 서버가 포트 {} 에서 시작됩니다.", disposableServer.port()))
			.doOnConnection(connection -> connection // 각 연결에 대해 적용되는 핸들러 추가
				.addHandlerLast(getInboundConnectionLogger())
				.addHandlerLast(getHandshakeHandler())
				.addHandlerLast(getChunkDecoder())
				.addHandlerLast(getChunkEncoder())
				.addHandlerLast(getRtmpMessageHandler()))
			.option(ChannelOption.SO_BACKLOG, 128) // 서버 소켓에 대한 설정 (연결 대기열의 최대 길이를 128로 설정)
			.childOption(ChannelOption.SO_KEEPALIVE, true) // TCP keep-alive 옵션 활성화
			.handle((in, out) -> in // 서버가 IO 이벤트를 처리하는 방식 정의
				.receiveObject() // 데이터 수신
				.cast(Stream.class) // Stream으로 형변환
					.flatMap(stream -> { // 각 스트림 객체에 대한 연산
							log.info("스트리머 {} 의 stream key가 유효합니다.", stream.getStreamName());
							stream.sendPublishMessage(); // 방송이 시작될 때 게시 알림
							stream.getReadyToBroadcast().thenRun(() -> webClient // 스트림이 방송을 시작할 준비가 되었을 때 실행, webClient를 이용하여 비동기 get 요청
								.get()
								//문제 발생 예상 코드 아래로 접속 요청을 보내는데, 아래 .retryWhen에서 정의한 3번의 재시도가 모두 실패했다는 Retries wchausted: 3/3 오류가 발생한다.
								.uri(transcodingServerIp + ":" + transcodingServerPort + "/transcode/" + stream.getStreamName()) // get요청을 보내는 uri
								.retrieve() // 응답 수신
								.bodyToMono(Long.class) // 서버의 응답을 Long 클래스로 매핑(Transcode 서버의 응답)
								.retryWhen(Retry.fixedDelay(3, Duration.ofMillis(1000))) // 재시도 로직
								.doOnError(error -> log.info("Transcoding 서버에서 다음의 에러가 발생했습니다 : " + error.getMessage()))
								.onErrorComplete() // 에러가 발생해도 무시하고 onComplete 메서드 실행
								.subscribe((s) -> { // 비동기적으로 실행되는 코드블록 정의
									log.info("Transcoding server started ffmpeg with pid " + s.toString());
									webClient
										.post() // 비동기 post 요청
										.uri(contentsServerIp + "/broadcasts/" + stream.getStreamName() + "/onair") // post 요청 uri (컨텐츠 서버)
										.retrieve() // 응답 수신
										.bodyToMono(Boolean.class) // 응답 형변환 (Boolean)
										.retryWhen(Retry.fixedDelay(3, Duration.ofMillis(500))) // 재시도
										.doOnError(e -> log.info(e.getMessage())) // 에러 정의
										.onErrorReturn(Boolean.FALSE) // 에러 발생 시 스트림의 종료를 방지
										.subscribeOn(Schedulers.parallel()) // 구독이 별도의 병렬 스레드에서 실행되도록 설정(비동기적 실행, 기존 스레드를 차단하지 않음)
										.subscribe((t) -> { // 비동기 작업 콜백 로깅
											if (t) {
												log.info("방송이 시작됩니다.");
											} else {
												log.info("ContentService 서버와 통신 에러 발생");
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
						.uri(authServerIp + "/broadcasts/" + stream.getStreamName() + "/check")
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
											.post()
											.uri(authServerIp + "/broadcasts/" + stream.getStreamName() + "/offair")
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
											.uri(authServerIp + "/broadcasts/" + stream.getStreamName() + "/onair")
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
			.bindNow(); // runner로 돌아가는 rtmp 서버 유지
		server.onDispose().block();
	}
}