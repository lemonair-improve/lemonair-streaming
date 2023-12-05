package com.hanghae.lemonairstreaming.Handler;

import com.hanghae.lemonairstreaming.rmtp.model.messages.RtmpConstants;
import com.hanghae.lemonairstreaming.rmtp.model.messages.RtmpMessage;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import lombok.extern.slf4j.Slf4j;


@Slf4j(topic = "ChunkEncoder")
public class ChunkEncoder extends MessageToByteEncoder<RtmpMessage> {

	private final long start = System.currentTimeMillis();
	private int chunkSize = RtmpConstants.RTMP_DEFAULT_CHUNK_SIZE;

	private boolean videoFirstMessage = true;
	private boolean audioFirstMessage = true;

	// RTMP의 message를 바이트로 변환
	// RTMP message의 header 타입에 따라 분기 처리
	@Override
	protected void encode(ChannelHandlerContext channelHandlerContext, RtmpMessage message, ByteBuf byteBuf) {
		log.info("message.header().getType() : " + message.header().getType());
		switch (message.header().getType()) {
			case RtmpConstants.RTMP_MSG_CONTROL_TYPE_SET_CHUNK_SIZE -> handleSetChunkSize(message, byteBuf);
			case RtmpConstants.RTMP_MSG_USER_CONTROL_TYPE_AUDIO -> handleAudioMessage(message, byteBuf);
			case RtmpConstants.RTMP_MSG_USER_CONTROL_TYPE_VIDEO -> handleVideoMessage(message, byteBuf);
			default -> handleDefault(message, byteBuf);
		}
	}


	// header 타입이 CHUNK_SIZE, AUDIO, VIDEO가 아닐 경우
	// encodeFmt0, encodeFmt3으로 처리
	private void handleDefault(RtmpMessage message, ByteBuf buf) {
		encodeFmt0(message, buf);
		encodeFmt3(message, buf);
	}


	// header 타입이 AUDIO
	// 첫번째 audio 메시지인 경우에는 handleDefault로 처리
	// 아닌 경우에는 encodeFmt1과 encodeFmt3으로 처리
	private void handleAudioMessage(RtmpMessage message, ByteBuf buf) {
		if (audioFirstMessage) {
			log.info("Audio config is sent");
			handleDefault(message, buf);
			audioFirstMessage = false;
		} else {
			encodeFmt1(message, buf);
			encodeFmt3(message, buf);
		}
	}

	// header 타입이 VIDEO
	// 첫번째 video 메시지인 경우에는 handleDefault로 처리
	// 아닌 경우에는 encodeFmt1과 encodeFmt3으로 처리
	private void handleVideoMessage(RtmpMessage message, ByteBuf buf) {
		if (videoFirstMessage) {
			log.info("Video config is sent");
			handleDefault(message, buf);
			videoFirstMessage = false;
		} else {
			encodeFmt1(message, buf);
			encodeFmt3(message, buf);
		}
	}

	// header 타입이 CHUNK_SIZE
	// chunkSize를 읽어오고 메시지에 대한 처리
	private void handleSetChunkSize(RtmpMessage message, ByteBuf buf) {
		chunkSize = message.payload().copy().readInt();
		handleDefault(message, buf);
	}


	// 왜 encodeFmt2인 경우는 없을까,,?

	// RTMP Fmt 타입이 0인 경우
	// Fmt 0이 필요로 하는 정보들을 채우고 메시지 형식을 생성
	private void encodeFmt0(RtmpMessage message, ByteBuf buf) {

		log.info("encodeFmt0");
		boolean extendedTimestamp = false;

		int cid = message.header().getCid();
		byte[] basicHeader = encodeFmtAndChunkId(RtmpConstants.RTMP_CHUNK_TYPE_0, cid);
		buf.writeBytes(basicHeader);

		long timestamp = System.currentTimeMillis() - start;

		if (timestamp >= RtmpConstants.RTMP_MAX_TIMESTAMP) {
			extendedTimestamp = true;
			buf.writeMedium(RtmpConstants.RTMP_MAX_TIMESTAMP);
		} else {
			buf.writeMedium((int) timestamp);
		}

		buf.writeMedium(message.header().getMessageLength());
		buf.writeByte(message.header().getType());
		buf.writeIntLE(message.header().getStreamId());

		if (extendedTimestamp) {
			buf.writeInt((int) timestamp);
		}

		int min = Math.min(chunkSize, message.payload().readableBytes());
		buf.writeBytes(message.payload(), min);
	}

	private void encodeFmt1(RtmpMessage message, ByteBuf buf) {
		log.info("encodeFmt1");

		int cid = message.header().getCid();
		byte[] basicHeader = encodeFmtAndChunkId(RtmpConstants.RTMP_CHUNK_TYPE_1, cid);
		buf.writeBytes(basicHeader);

		buf.writeMedium(message.header().getTimestampDelta());
		buf.writeMedium(message.header().getMessageLength());
		buf.writeByte(message.header().getType());

		int min = Math.min(chunkSize, message.payload().readableBytes());
		buf.writeBytes(message.payload(), min);
	}

	private void encodeFmt3(RtmpMessage message, ByteBuf buf) {
		log.info("encodeFmt3");

		int cid = message.header().getCid();
		byte[] basicHeader = encodeFmtAndChunkId(RtmpConstants.RTMP_CHUNK_TYPE_3, cid);
		while (message.payload().isReadable()) {
			buf.writeBytes(basicHeader);

			int min = Math.min(chunkSize, message.payload().readableBytes());
			buf.writeBytes(message.payload(), min);
		}
	}

	private byte[] encodeFmtAndChunkId(int fmt, int cid) {
		if (cid >= 64 + 255) {
			return new byte[]{
				(byte) ((fmt << 6) | 1),
				(byte) ((cid - 64) & 0xff),
				(byte) (((cid - 64) >> 8) & 0xff)};
		} else if (cid >= 64) {
			return new byte[]{
				(byte) ((fmt << 6)),
				(byte) ((cid - 64) & 0xff)
			};
		} else {
			return new byte[]{(byte) ((fmt << 6) | cid)};
		}
	}
}