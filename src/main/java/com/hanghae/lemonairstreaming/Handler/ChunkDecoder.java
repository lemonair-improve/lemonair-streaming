package com.hanghae.lemonairstreaming.Handler;

// import com.example.streamingservice.rtmp.model.messages.RtmpHeader;
// import com.example.streamingservice.rtmp.model.messages.RtmpMessage;
// import com.example.streamingservice.rtmp.model.util.MessageProvider;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.hanghae.lemonairstreaming.rmtp.model.messages.RtmpConstants;
import com.hanghae.lemonairstreaming.rmtp.model.messages.RtmpHeader;
import com.hanghae.lemonairstreaming.rmtp.model.messages.RtmpMessage;
import com.hanghae.lemonairstreaming.rmtp.model.util.MessageProvider;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ReplayingDecoder;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ChunkDecoder extends ReplayingDecoder<ChunkDecoder.DecodeState> {

	private int clientChunkSize = RtmpConstants.RTMP_DEFAULT_CHUNK_SIZE;
	private int ackSize;
	private int bytesReceived;
	private int lastResponseSize;

	private final Map<Integer, RtmpHeader> completeHeaders = new HashMap<>();
	private final Map<Integer, ByteBuf> payloadParts = new HashMap<>(4);

	private RtmpHeader currentHeader;
	private ByteBuf currentPayload;

	public enum DecodeState {
		READ_HEADER, PROCESS_HEADER, PROCESS_PAYLOAD
	}

	@Override
	protected void decode(ChannelHandlerContext channelHandlerContext, ByteBuf byteBuf, List<Object> out) throws Exception {
		// log.info("디코딩 시작");
		DecodeState state = state();
		if (state == null) {
			state = DecodeState.READ_HEADER;
		}

		switch (state) {
			case READ_HEADER -> {
				// log.info("READ_HEADER state");
				currentHeader = readHeader(byteBuf);
				restoreHeader(currentHeader);
				checkpoint(DecodeState.PROCESS_HEADER);
			}
			case PROCESS_HEADER -> {
				// log.info("PROCESS_HEADER state");
				int messageLength = currentHeader.getMessageLength();

				if (currentHeader.getFmt() != RtmpConstants.RTMP_CHUNK_TYPE_3) {
					ByteBuf buf = Unpooled.buffer(messageLength, messageLength);
					payloadParts.put(currentHeader.getCid(), buf);
					completeHeaders.put(currentHeader.getCid(), currentHeader);
				}
				// Rare case when format 3 encoding is used and body completely read
				payloadParts.putIfAbsent(currentHeader.getCid(), Unpooled.buffer(messageLength, messageLength));

				currentPayload = payloadParts.get(currentHeader.getCid());

				checkpoint(DecodeState.PROCESS_PAYLOAD);
			}
			case PROCESS_PAYLOAD -> {
				// log.info("PROCESS_PAYLOAD state");
				byte[] bytes = new byte[Math.min(clientChunkSize, currentPayload.writableBytes())];
				byteBuf.readBytes(bytes);
				currentPayload.writeBytes(bytes);
				checkpoint(DecodeState.READ_HEADER);

				if (currentPayload.isWritable()) {
					return;
				}

				payloadParts.remove(currentHeader.getCid());

				RtmpMessage message = new RtmpMessage(currentHeader, currentPayload);

				sendAcknowledgement(channelHandlerContext, currentHeader.getHeaderLength() + currentHeader.getMessageLength());

				switch (currentHeader.getType()) {
					case RtmpConstants.RTMP_MSG_CONTROL_TYPE_SET_CHUNK_SIZE -> handleChunkSize(currentPayload);
					case RtmpConstants.RTMP_MSG_CONTROL_TYPE_WINDOW_ACKNOWLEDGEMENT_SIZE -> handleWindowAckSize(currentPayload);
					case RtmpConstants.RTMP_MSG_CONTROL_TYPE_ACKNOWLEDGEMENT -> handleAck(currentPayload);
					case RtmpConstants.RTMP_MSG_CONTROL_TYPE_ABORT -> handleAbort(currentPayload);
					default -> out.add(message);
				}
			}
		}
	}

	private RtmpHeader readHeader(ByteBuf buf) {

		RtmpHeader header = new RtmpHeader();
		int headerLength = 0;
		byte firstByte = buf.readByte();
		headerLength++;

		// Decode Basic Header
		int fmt = (firstByte & 0xff) >> 6;
		int cid = firstByte & 0x3f;

		if (cid == 0) {
			// 2 byte form
			cid = buf.readByte() & 0xff + 64;
			headerLength++;
		} else if (cid == 1) {
			// 3 byte form
			byte secondByte = buf.readByte();
			byte thirdByte = buf.readByte();
			cid = (thirdByte & 0xff) * 256 + (secondByte & 0xff) + 64;
			headerLength += 2;
		}

		header.setCid(cid);
		header.setFmt(fmt);

		// Read Message Header
		switch (fmt) {
			case RtmpConstants.RTMP_CHUNK_TYPE_0 -> {
				// log.info("RTMP_CHUNK_TYPE_0");
				int timestamp = buf.readMedium();
				int messageLength = buf.readMedium();
				short type = (short) (buf.readByte() & 0xff);
				// This field occupies 4 bytes in the chunk header in little endian format.
				int messageStreamId = buf.readIntLE();
				headerLength += 11;
				// Presence of extended timestamp
				if (timestamp == RtmpConstants.RTMP_MAX_TIMESTAMP) {
					long extendedTimestamp = buf.readInt();
					header.setExtendedTimestamp(extendedTimestamp);
					headerLength += 4;
				}

				header.setTimestamp(timestamp);
				header.setMessageLength(messageLength);
				header.setType(type);
				header.setStreamId(messageStreamId);
			}
			case RtmpConstants.RTMP_CHUNK_TYPE_1 -> {
				// log.info("RTMP_CHUNK_TYPE_1");
				int timestampDelta = buf.readMedium();
				int messageLength = buf.readMedium();
				short type = (short) (buf.readByte() & 0xff);

				headerLength += 7;
				// Presence of extended timestamp
				if (timestampDelta == RtmpConstants.RTMP_MAX_TIMESTAMP) {
					long extendedTimestamp = buf.readInt();
					header.setExtendedTimestamp(extendedTimestamp);
					headerLength += 4;
				}

				header.setTimestampDelta(timestampDelta);
				header.setMessageLength(messageLength);
				header.setType(type);
			}
			case RtmpConstants.RTMP_CHUNK_TYPE_2 -> {
				// log.info("RTMP_CHUNK_TYPE_2");
				int timestampDelta = buf.readMedium();
				headerLength += 3;
				// Presence of extended timestamp
				if (timestampDelta == RtmpConstants.RTMP_MAX_TIMESTAMP) {
					long extendedTimestamp = buf.readInt();
					header.setExtendedTimestamp(extendedTimestamp);
					headerLength += 4;
				}
				header.setTimestampDelta(timestampDelta);

			}
            /*
            Type 3 chunks have no message header.
            The stream ID, message length and timestamp delta fields are not present;
            chunks of this type take values from the preceding chunk for the same Chunk Stream ID
            */
			case RtmpConstants.RTMP_CHUNK_TYPE_3 -> {/* Do nothing */}
			default -> {
				log.error("readHeader 함수에서 switch문에 걸리지 않음");
				log.error("fmt :" + fmt);
				throw new RuntimeException("Illegal format type");
			}
		}
		header.setHeaderLength(headerLength);

		return header;
	}

	private void restoreHeader(RtmpHeader header) {
		int cid = header.getCid();
		RtmpHeader completeHeader = completeHeaders.get(cid);
		if (completeHeader == null) {
			return;
		}
		switch (header.getFmt()) {
			case RtmpConstants.RTMP_CHUNK_TYPE_3 -> {
				header.setStreamId(completeHeader.getStreamId());
				header.setTimestamp(completeHeader.getTimestamp());
				header.setTimestampDelta(completeHeader.getTimestampDelta());
				header.setMessageLength(completeHeader.getMessageLength());
				header.setType(completeHeader.getType());
			}
			case RtmpConstants.RTMP_CHUNK_TYPE_2 -> {
				header.setStreamId(completeHeader.getStreamId());
				header.setTimestamp(completeHeader.getTimestamp());
				header.setMessageLength(completeHeader.getMessageLength());
				header.setType(completeHeader.getType());
			}
			case RtmpConstants.RTMP_CHUNK_TYPE_1 -> {
				header.setStreamId(completeHeader.getStreamId());
				header.setTimestamp(completeHeader.getTimestamp());
			}
		}
	}

	/*
	The client or the server MUST send an acknowledgment to the peer after receiving bytes equal to the window size.
	The window size is the maximum number of bytes that the sender sends without receiving acknowledgment from the receiver.
	This message specifies the sequence number, which is the number of the bytes received so far.
		SEQUENCE NUMBER (32 bits): This field holds the number of bytes received so far.
	*/
	private void sendAcknowledgement(ChannelHandlerContext channelHandlerContext, int inSize) {
		bytesReceived += inSize;
		// handle overflow
		if (bytesReceived > 0x70000000) {
			channelHandlerContext.writeAndFlush(MessageProvider.acknowledgement(bytesReceived));
			bytesReceived = 0;
			lastResponseSize = 0;
			return;
		}

		if (ackSize > 0 && bytesReceived - lastResponseSize >= ackSize) {
			lastResponseSize = bytesReceived;
			channelHandlerContext.writeAndFlush(MessageProvider.acknowledgement(lastResponseSize));
		}
	}

	private void handleWindowAckSize(ByteBuf payload) {
		ackSize = payload.readInt();
		payload.release();
	}

	private void handleChunkSize(ByteBuf payload) {
		clientChunkSize = payload.readInt();
		payload.release();
	}

	private void handleAck(ByteBuf payload) {
		payload.release();
	}

	private void handleAbort(ByteBuf payload) {
		payload.release();
	}
}