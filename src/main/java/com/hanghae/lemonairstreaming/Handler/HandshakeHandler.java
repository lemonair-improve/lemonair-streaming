package com.hanghae.lemonairstreaming.Handler;

import java.util.List;
import java.util.Random;

import com.hanghae.lemonairstreaming.rmtp.model.messages.RtmpConstants;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class HandshakeHandler extends ByteToMessageDecoder {

	private boolean C0C1;
	private boolean completed;
	private int timestamp;

	private byte[] clientBytes = new byte[RtmpConstants.RTMP_HANDSHAKE_SIZE - 8];

	@Override
	protected void decode(ChannelHandlerContext channelHandlerContext, ByteBuf byteBuf, List<Object> list) {
		if (completed) {
			channelHandlerContext.fireChannelRead(byteBuf);
			return;
		}

		if (!C0C1) {
			byte version = byteBuf.readByte();
			if (!(version == RtmpConstants.RTMP_VERSION)) {
				log.info("Client requests unsupported version: " + version);
			}
			timestamp = byteBuf.readInt();
			byteBuf.readInt();
			byteBuf.readBytes(clientBytes);

			generateS0S1S2(channelHandlerContext);
			C0C1 = true;
		} else {
			byteBuf.readInt();
			byteBuf.readInt();
			byteBuf.readBytes(clientBytes);

			clientBytes = null;
			completed = true;
			channelHandlerContext.channel().pipeline().remove(this);
		}
	}

	private void generateS0S1S2(ChannelHandlerContext channelHandlerContext) {
		ByteBuf resp = Unpooled.buffer(RtmpConstants.RTMP_HANDSHAKE_VERSION_LENGTH + RtmpConstants.RTMP_HANDSHAKE_SIZE
			+ RtmpConstants.RTMP_HANDSHAKE_SIZE);
		resp.writeByte(RtmpConstants.RTMP_VERSION);

		resp.writeInt(0);
		resp.writeInt(0);
		resp.writeBytes(randomBytes(RtmpConstants.RTMP_HANDSHAKE_SIZE - 8));

		resp.writeInt(timestamp);
		resp.writeInt(0);
		resp.writeBytes(randomBytes(RtmpConstants.RTMP_HANDSHAKE_SIZE - 8));

		channelHandlerContext.writeAndFlush(resp);
	}

	private byte[] randomBytes(int length) {
		byte[] bytes = new byte[length];
		new Random().nextBytes(bytes);
		return bytes;
	}
}