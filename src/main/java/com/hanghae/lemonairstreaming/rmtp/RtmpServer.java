package com.hanghae.lemonairstreaming.rmtp;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.web.reactive.function.client.WebClient;

import com.hanghae.lemonairstreaming.Handler.ChunkDecoder;
import com.hanghae.lemonairstreaming.Handler.ChunkEncoder;
import com.hanghae.lemonairstreaming.Handler.HandshakeHandler;
import com.hanghae.lemonairstreaming.Handler.InboundConnectionLogger;
import com.hanghae.lemonairstreaming.Handler.RtmpMessageHandler;
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
	@Value("${external.service.server.ip}")
	private String serviceServerIp;

	@Value("${external.service.server.port}")
	private int ServiceServerPort;

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
		runWithExtractedMethod(args);
	}

	private void runWithExtractedMethod(String... args) {
		DisposableServer server = TcpServer.create() // TCP 서버 생성
			.port(rtmpPort) // RTMP 포트 설정 (1935)
			.doOnBound(disposableServer -> log.info("RTMP 서버가 포트 {} 에서 시작됩니다.", disposableServer.port()))
			.doOnConnection(connection -> connection // 각 연결에 대해 적용되는 핸들러 추가
				.addHandlerLast(getInboundConnectionLogger())
				.addHandlerLast(getHandshakeHandler())
				.addHandlerLast(getChunkDecoder())
				.addHandlerLast(getChunkEncoder())
				.addHandlerLast(getRtmpMessageHandler()))
			.option(ChannelOption.SO_BACKLOG, 128) // 서버 소켓에 대한 설정 (연결 대기열의 최대 길이를 128로 설정)
			.childOption(ChannelOption.SO_KEEPALIVE, true) // TCP keep-alive 옵션 활성화
			.handle((in, out) -> in.receiveObject() // 데이터 수신
				.cast(Stream.class)// Stream으로 형변환
				.doOnError((e) -> log.error("지원하지 않는 스트림 데이터 형식입니다. obs studio를 사용하세요"))
				.onErrorComplete()
				.flatMap(stream -> { // 각 스트림 객체에 대한 연산
					log.info("스트리머 {} 의 stream key가 유효합니다.", stream.getStreamName());
					stream.sendPublishMessage();
					requestTranscoding(stream);
					return Mono.empty(); // rtmp 프로토콜로 들어온 영상 송출 요청에 대한 작업이 종료되었음을 나타낸다.
				})
				.then())
			.bindNow(); // 서버의 환경 설정과 구독 관계 설정이 끝나면 서버가 BindNow된다.
		server.onDispose().block(); // .block()으로 서버가 onDispose()되어 Mono가 반환될때까지 기다린다.
	}

	private CompletableFuture<Void> requestTranscoding(Stream stream) {
		return stream.getReadyToBroadcast()
			.thenRun(() -> webClient // 스트림이 방송을 시작할 준비가 되었을 때 실행, webClient를 이용하여 비동기 get 요청
				.get()
				//문제 발생 예상 코드 아래로 접속 요청을 보내는데, 아래 .retryWhen에서 정의한 3번의 재시도가 모두 실패했다는 Retries wchausted: 3/3 오류가 발생한다.
				.uri(transcodingServerIp + ":" + transcodingServerPort + "/transcode/"
					+ stream.getStreamName()) // get요청을 보내는 uri
				.retrieve() // 응답 수신
				// transcoding service의 응답은 받은 이후 사용되지 않으며
				// 그냥 응답이 오는지 안오는지가 중요하다.
				// 응답이 정상적으로 온다면 트랜스코딩이 계속해서 진행중,
				// 응답이 정상적으로 오지 않는다면 트랜스코딩 서버에서 문제가 발생한 것임.
				.bodyToMono(Long.class) // 서버의 응답을 Long 클래스로 매핑(Transcode 서버의 응답)
				.retryWhen(Retry.fixedDelay(3, Duration.ofMillis(1000))) // 재시도 로직
				.doOnError(error -> log.info("Transcoding 서버에서 다음의 에러가 발생했습니다 : " + error.getMessage()))
				.onErrorComplete() // 에러가 발생해도 무시하고 onComplete 메서드 실행
				.subscribe((s) -> sendStreamingIsOnAirToServiceServer(stream, s)));
	}

	private void sendStreamingIsOnAirToServiceServer(Stream stream, Long s) {
		log.info("transcoding 서비스 구독 시작  pid : " + s.toString());
		webClient.post() // 비동기 post 요청
			.uri(serviceServerIp + "/api/streams/" + stream.getStreamName() + "/streaming") // post 요청 uri (컨텐츠 서버)
			.retrieve() // 응답 수신
			.bodyToMono(Boolean.class) // 응답 형변환 (Boolean)
			.retryWhen(Retry.fixedDelay(3, Duration.ofMillis(500))) // 재시도
			.doOnError(e -> log.info(e.getMessage())) // 에러 정의
			.onErrorReturn(Boolean.FALSE) // 에러 발생 시 스트림의 종료를 방지
			.subscribeOn(Schedulers.parallel()) // contents 서버를 구독하는 작업이 병렬 스레드에서 실행되도록 설정(비동기적 실행, 기존 스레드를 차단하지 않음)
			.subscribe((t) -> { // 비동기 작업 콜백 로깅
				if (t) {
					log.info("방송이 시작됩니다.");
				} else {
					log.info("Service 서버와 통신 에러 발생");
				}
			});
	}
}