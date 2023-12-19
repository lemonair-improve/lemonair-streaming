package com.hanghae.lemonairstreaming.Handler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.reactive.function.client.WebClient;

import com.hanghae.lemonairstreaming.Amf0Rules;
import com.hanghae.lemonairstreaming.rmtp.model.Stream;
import com.hanghae.lemonairstreaming.rmtp.model.StreamContext;
import com.hanghae.lemonairstreaming.rmtp.model.messages.RtmpConstants;
import com.hanghae.lemonairstreaming.rmtp.model.messages.RtmpMediaMessage;
import com.hanghae.lemonairstreaming.rmtp.model.messages.RtmpMessage;
import com.hanghae.lemonairstreaming.rmtp.model.util.MessageProvider;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import lombok.extern.slf4j.Slf4j;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

@Slf4j
public class RtmpMessageHandler extends MessageToMessageDecoder<RtmpMessage> {

	private final StreamContext context;
	@Autowired
	WebClient webClient;
	private String currentSessionStream;

	@Value("${external.service.server.ip}")
	private String serviceServerIp;

	@Value("${external.service.server.port}")
	private int serviceServerPort;
	public RtmpMessageHandler(StreamContext context) {
		this.context = context;
	}

	// 핸들러가 제거될 때 호출
	// 스트림의 퍼블리셔 아이디와 채널의 아이디가 같을 경우 스트림을 닫고 삭제
	@Override
	public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
		Stream stream = context.getStream(currentSessionStream);
		if (stream != null) {
			if (ctx.channel().id().equals(stream.getPublisher().id())) {
				stream.closeStream();
				context.deleteStream(currentSessionStream);
			}
		}
		super.handlerRemoved(ctx);
	}

	// RTMP 프로토콜에서 받은 메시지를 디코딩하고 유형에 따라 처리
	// COMMAND_TYPE_AMF0, DATA_TYPE_AMF0, AUDIO, VIDEO, EVENT
	@Override
	protected void decode(ChannelHandlerContext channelHandlerContext, RtmpMessage in, List<Object> out) {
		short type = in.header().getType();
		ByteBuf payload = in.payload();

		switch (type) {
			case RtmpConstants.RTMP_MSG_COMMAND_TYPE_AMF0 -> handleCommand(channelHandlerContext, payload, out);
			case RtmpConstants.RTMP_MSG_DATA_TYPE_AMF0 -> handleData(payload);
			case RtmpConstants.RTMP_MSG_USER_CONTROL_TYPE_AUDIO,
				RtmpConstants.RTMP_MSG_USER_CONTROL_TYPE_VIDEO -> handleMedia(in);
			case RtmpConstants.RTMP_MSG_USER_CONTROL_TYPE_EVENT -> handleEvent(in);
			default -> log.info("Unsupported message/ Type id: {}", type);
		}
		// Clear ByteBUf
		payload.release();
	}

	// RtmpConstants.RTMP_MSG_COMMAND_TYPE_AMF0 일 때 수행되는 메서드
	// 명령어에 따라 분기 처리
	// connect, createStream 등
	private void handleCommand(ChannelHandlerContext ctx, ByteBuf payload, List<Object> out) {
		List<Object> decoded = Amf0Rules.decodeAll(payload);
		String command = (String)decoded.get(0);
		log.info("handleCommand method :" + command + ">>>" + decoded);
		switch (command) {
			case "connect" -> onConnect(ctx, decoded);
			case "createStream" -> onCreate(ctx, decoded);
			case "publish" -> onPublish(ctx, decoded, out);
			case "play" -> onPlay(ctx, decoded);
			case "closeStream" -> onClose(ctx);
			case "deleteStream" -> onDelete(ctx);
			default -> log.info("Unsupported command type {}", command);
		}
	}

	// RtmpConstants.RTMP_MSG_COMMAND_TYPE_AMF0 명령어가 connect일 때 수행하는 메서드
	// 연결을 처리하는 과정
	private void onConnect(ChannelHandlerContext ctx, List<Object> message) {
		log.info("Client connection from {}, channel id is {}", ctx.channel().remoteAddress(), ctx.channel().id());

		String app = (String)((Map<String, Object>)message.get(2)).get("app");
		log.info(message.toString());

		Integer clientEncodingFormat = (Integer)((Map<String, Object>)message.get(2)).get("objectEncoding");

		if (clientEncodingFormat != null && clientEncodingFormat == 3) {
			log.error("AMF3 format is not supported. Closing connection to {}", ctx.channel().remoteAddress());
			ctx.close();
			return;
		}

		this.currentSessionStream = app;

		// window acknowledgement size
		//log.info("Sending window ack size message");
		ctx.writeAndFlush(MessageProvider.setWindowAcknowledgement(RtmpConstants.RTMP_DEFAULT_OUTPUT_ACK_SIZE));

		// set peer bandwidth
		//log.info("Sending set peer bandwidth message");
		ctx.writeAndFlush(MessageProvider.setPeerBandwidth(RtmpConstants.RTMP_DEFAULT_OUTPUT_ACK_SIZE, 2));

		// set chunk size
		//log.info("Sending set chunk size message");
		ctx.writeAndFlush(MessageProvider.setChunkSize(RtmpConstants.RTMP_DEFAULT_CHUNK_SIZE));

		List<Object> result = new ArrayList<>();

		Amf0Rules.Amf0Object cmdObj = new Amf0Rules.Amf0Object();
		cmdObj.put("fmsVer", "FMS/3,0,1,123");
		cmdObj.put("capabilities", 31);

		Amf0Rules.Amf0Object info = new Amf0Rules.Amf0Object();
		info.put("level", "status");
		info.put("code", "NetConnection.Connect.Success");
		info.put("description", "Connection succeeded");
		info.put("objectEncoding", 0);

		result.add("_result");
		result.add(message.get(1)); //transaction id
		result.add(cmdObj);
		result.add(info);

		ctx.writeAndFlush(MessageProvider.commandMessage(result));
	}

	// RtmpConstants.RTMP_MSG_COMMAND_TYPE_AMF0 명령어가 createStream일 때 수행하는 메서드
	// transaction ID와 stream ID를 포함해서 클라이언트에 반환
	private void onCreate(ChannelHandlerContext ctx, List<Object> message) {
		log.info("Create stream");

		List<Object> result = new ArrayList<>();
		result.add("_result");
		result.add(message.get(1)); // transaction id
		result.add(null); // properties
		result.add(RtmpConstants.RTMP_DEFAULT_MESSAGE_STREAM_ID_VALUE); // stream id

		ctx.writeAndFlush(MessageProvider.commandMessage(result));
	}

	// RtmpConstants.RTMP_MSG_COMMAND_TYPE_AMF0 명령어가 publish 때 수행하는 메서드
	// 스트리밍을 게시할 때 실행
	// 스트리밍 유형이 live가 아니면 연결 종료
	// 스트리밍 세션을 생성하고 클라이언트로부터 전달받은 비밀키를 설정
	// 세션을 서버 컨텍스트에 추가하고, output (출력리스트)에 추가
	private void onPublish(ChannelHandlerContext ctx, List<Object> message, List<Object> output) {
		log.info("Stream publishing");
		String streamType = (String)message.get(4);
		if (!"live".equals(streamType)) {
			log.error("Stream type {} is not supported", streamType);
			ctx.channel().disconnect();
		}

		Stream stream = new Stream(currentSessionStream);
		String secret = (String)message.get(3);
		stream.setStreamKey(secret);
		stream.setPublisher(ctx.channel());
		context.addStream(stream);

		// Push stream further and handle everything(metadata, credentials, etc)
		output.add(stream);
	}

	// RtmpConstants.RTMP_MSG_COMMAND_TYPE_AMF0 명령어가 play일 때 수행하는 메서드
	// 시청자 입장일 때 수행하는 메서드
	private void onPlay(ChannelHandlerContext ctx, List<Object> message) {
		// String secret = (String) message.get(3);

		Stream stream = context.getStream(currentSessionStream);
		if (stream != null) {
			ctx.writeAndFlush(MessageProvider.userControlMessageEvent(RtmpConstants.STREAM_BEGIN));
			ctx.writeAndFlush(MessageProvider.onStatus("status", "NetStream.Play.Start", "Strat live"));

			List<Object> args = new ArrayList<>();
			args.add("|RtmpSampleAccess");
			args.add(true);
			args.add(true);
			ctx.writeAndFlush(MessageProvider.commandMessage(args));

			List<Object> metadata = new ArrayList<>();
			metadata.add("onMetaData");
			metadata.add(stream.getMetadata());
			ctx.writeAndFlush(MessageProvider.dataMessage(metadata));

			stream.addSubscriber(ctx.channel());

		} else {
			log.info("Stream doesn't exist");
			ctx.writeAndFlush(MessageProvider.onStatus("error", "NetStream.Play.StreamNotFound", "No Such Stream"));
			ctx.channel().close();
		}
	}

	// RtmpConstants.RTMP_MSG_COMMAND_TYPE_AMF0 명령어가 closeStream 때 수행하는 메서드
	// 스트리밍이 종료될 때 호출
	private void onClose(ChannelHandlerContext ctx) {
		Stream stream = context.getStream(currentSessionStream);
		if (stream == null) {
			ctx.writeAndFlush(MessageProvider.onStatus("status", "NetStream.Unpublish.Success", "Stop publishing"));
		} else if (ctx.channel().id().equals(stream.getPublisher().id())) {
			ctx.writeAndFlush(MessageProvider.onStatus("status", "NetStream.Unpublish.Success", "Stop publishing"));

			webClient
				.post()
				.uri(serviceServerIp + ":" +serviceServerPort + "/api/rtmp/streams/" + stream.getStreamerId() + "/stop")

				.retrieve()
				.bodyToMono(Boolean.class)
				.retryWhen(Retry.fixedDelay(3, Duration.ofMillis(500)))
				.doOnError(e -> log.info(e.getMessage()))
				.onErrorReturn(Boolean.FALSE)
				.subscribeOn(Schedulers.parallel())
				.subscribe((s) -> {
					if (s) {
						log.info("방송이 종료됩니다.");
					} else {
						log.info("ContentService 서버와 통신 에러 발생");
					}
				});
			stream.closeStream();
			context.deleteStream(stream.getStreamerId());
			ctx.close();
		} else {
			log.info("Subscriber closed stream");
		}
	}

	// RtmpConstants.RTMP_MSG_COMMAND_TYPE_AMF0 명령어가 deleteStream 때 수행하는 메서드
	private void onDelete(ChannelHandlerContext ctx) {
		onClose(ctx);
	}

	// 데이터 처리 메서드

	// OBS 클라이언트를 감지하여 스트리밍의 메타데이터를 업데이트
	// Youtube 스트리밍을 추가하려면 이 로직을 참고해야 할 듯
	private void handleData(ByteBuf payload) {
		List<Object> decoded = Amf0Rules.decodeAll(payload);
		String dataType = (String)decoded.get(0);
		if ("@setDataFrame".equals(dataType)) {
			// handle metadata
			Map<String, Object> metadata = (Map<String, Object>)decoded.get(2);
			metadata.remove("filesize");
			String encoder = (String)metadata.get("encoder");
			if (encoder != null && encoder.contains("obs")) {
				log.info("OBS client detected");
			}
			Stream stream = context.getStream(this.currentSessionStream);
			if (stream != null) {
				log.info("Stream metadata set");
				stream.setMetadata(metadata);
			}
		}
	}

	// 스트림에 미디어 메시지(Video, Audio) 추가
	private void handleMedia(RtmpMessage message) {
		Stream stream = context.getStream(currentSessionStream);
		if (stream != null) {
			stream.addMedia(RtmpMediaMessage.fromRtmpMessage(message));
		} else {
			log.info("Stream does not exist");
		}
	}

	// 기타 이벤트에 대한 처리
	private void handleEvent(RtmpMessage message) {
		log.info("User event type {}, value {}", message.payload().readShort(), message.payload().readInt());
	}
}