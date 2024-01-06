package com.hanghae.lemonairstreaming.Handler;

import java.net.SocketException;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class InboundConnectionLogger extends ChannelInboundHandlerAdapter {

	@Override
	public void handlerAdded(ChannelHandlerContext ctx) {
		if (ctx.channel().isActive()) {
			log.info("채널 시작");
		}
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) {
		log.info("채널 종료");
		ctx.fireChannelInactive();
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		if (cause instanceof SocketException) {
			log.info("Socket closed");
		} else {
			log.error("Error occured. Address: " + ctx.channel().remoteAddress(), cause);
		}
	}
}