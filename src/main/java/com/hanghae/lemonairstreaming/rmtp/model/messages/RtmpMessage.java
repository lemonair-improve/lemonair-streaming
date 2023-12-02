package com.hanghae.lemonairstreaming.rmtp.model.messages;

import io.netty.buffer.ByteBuf;

public record RtmpMessage(RtmpHeader header, ByteBuf payload) {
}