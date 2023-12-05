package com.hanghae.lemonairstreaming.rmtp.model;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;

import com.hanghae.lemonairstreaming.rmtp.model.messages.RtmpConstants;
import com.hanghae.lemonairstreaming.rmtp.model.messages.RtmpMediaMessage;
import com.hanghae.lemonairstreaming.rmtp.model.messages.RtmpMessage;
import com.hanghae.lemonairstreaming.rmtp.model.util.MessageProvider;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Getter
@Setter
@Slf4j
public class Stream {

	private Map<String, Object> metadata;

	private Channel publisher;

	private final Set<Channel> subscribers;
	private final String streamName;
	private String streamKey;

	private final BlockingQueue<RtmpMediaMessage> rtmpGopCache;

	private RtmpMediaMessage videoConfig;
	private RtmpMediaMessage audioConfig;

	private CompletableFuture<Boolean> readyToBroadcast;

	public Stream(String streamName) {
		this.streamName = streamName;
		this.subscribers = new LinkedHashSet<>();
		this.rtmpGopCache = new ArrayBlockingQueue<>(1024); // Group of Pictures 동영상 압축 기법 1024 화질인 듯
		this.readyToBroadcast = new CompletableFuture<>();
	}

	public void addMedia(RtmpMediaMessage message) {
		short type = message.header().getType();

		if (type == (short) RtmpConstants.RTMP_MSG_USER_CONTROL_TYPE_AUDIO) {
			if (message.isAudioConfig()) {
				log.info("Audio config is set");
				audioConfig = message;
			}
		} else if (type == (short) RtmpConstants.RTMP_MSG_USER_CONTROL_TYPE_VIDEO) {
			if (message.isVideoConfig()) {
				log.info("Video config is set");
				videoConfig = message;
			}
			// clear interFrames queue
			// keyframe은 독립적인 이미지 정보이기 때문에 이전에 쌓인 gop 캐시를 지워준다
			if (message.isKeyframe()) {
				log.info("Keyframe added. {} frames cleared", rtmpGopCache.size());
				rtmpGopCache.clear();
			}
		}
		rtmpGopCache.add(message);
		broadcastMessage(message);
	}

	// 스트림에서 수신된 미디어 메시지를 스트림을 구독하는 모든 채널에 브로드캐스팅
	public void broadcastMessage(RtmpMediaMessage message) {
		if (!readyToBroadcast.isDone()) {
			readyToBroadcast.complete(Boolean.TRUE);
		}
		Iterator<Channel> channelIterator = subscribers.iterator();
		while (channelIterator.hasNext()) {
			Channel next = channelIterator.next();
			if (next.isActive()) {
				next.writeAndFlush(RtmpMediaMessage.toRtmpMessage(message));
			} else {
				log.info("Inactive channel detected");
				channelIterator.remove();
			}
		}
	}

	// 스트림에 새로운 구독자(channel) 추가
	// 비디오, 오디오 구성 전송
	// gop 캐시된 rtmp 메시지 전송
	public void addSubscriber(Channel channel) {
		log.info("Subscriber {} added to stream {}", channel.remoteAddress(), streamName);
		subscribers.add(channel);

		channel.writeAndFlush(RtmpMediaMessage.toRtmpMessage(videoConfig));
		channel.writeAndFlush(RtmpMediaMessage.toRtmpMessage(audioConfig));

		log.info("Sending group of pictures to client");
		for (RtmpMediaMessage message : rtmpGopCache) {
			channel.writeAndFlush(RtmpMediaMessage.toRtmpMessage(message));
		}
	}

	// 스트림 종료
	// 채널의 구독자들에게 알림
	public void closeStream() {
		log.info("Closing stream");
		RtmpMessage eof = MessageProvider.userControlMessageEvent(RtmpConstants.STREAM_EOF);
		for (Channel channel : subscribers) {
			channel.writeAndFlush(eof).addListener(ChannelFutureListener.CLOSE);
		}
	}

	// 스트림 게시 알림
	public void sendPublishMessage() {
		publisher.writeAndFlush(MessageProvider.onStatus(
			"status",
			"NetStream.Publish.Start",
			"Start publishing"));
	}

}