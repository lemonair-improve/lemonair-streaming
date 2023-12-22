package com.hanghae.lemonairstreaming.rmtp.model.messages;

public class RtmpConstants {

	public static final byte RTMP_VERSION = 3;
	public static final int RTMP_HANDSHAKE_SIZE = 1536;
	public static final int RTMP_HANDSHAKE_VERSION_LENGTH = 1;
	public static final int RTMP_MAX_TIMESTAMP = 0XFFFFFF;
	public static final int RTMP_DEFAULT_CHUNK_SIZE = 128;


	public static final int RTMP_DEFAULT_OUTPUT_ACK_SIZE = 5_000_000;
	public static final int RTMP_DEFAULT_OUTPUT_CHUNK_SIZE = 5000;
	/*
	 *   The message stream ID can be any arbitrary value.
	 *   Different message streams multiplexed onto the same chunk stream are demultiplexed based on their message stream IDs.
	 *   Beyond that, as far as RTMP Chunk Stream is concerned, this is an opaque value.
	 *   This field occupies 4 bytes in the chunk header in little endian format.
	 * */
	public static final int RTMP_DEFAULT_MESSAGE_STREAM_ID_VALUE = 42;

	/**
	 * RTMP 프로토콜 시작시
 	 */
	public static final int RTMP_CHUNK_TYPE_0 = 0; // 11-bytes: timestamp(3) + length(3) + stream type(1) + stream id(4)
	/**
	 * RTMP 이전 청크와 동일한 스트림이며, 현재 메시지의 타임스탬프의 길이가
	 * 이전 청크와 동일하다고 가정하는 경우 적합
	 */
	public static final int RTMP_CHUNK_TYPE_1 = 1; // 7-bytes: delta(3) + length(3) + stream type(1)
	public static final int RTMP_CHUNK_TYPE_2 = 2; // 3-bytes: delta(3)

	/**
	 * RTMP 이전 청크와 동일한 스트림이며, 이전 청크의 헤더 정보를 재사용함,
	 * 작은 조각으로 나뉘어 여러번 전송될 때 유리함
	 */
	public static final int RTMP_CHUNK_TYPE_3 = 3; // 0-byte

	/* Protocol Control Messages */
	public static final int RTMP_MSG_CONTROL_TYPE_SET_CHUNK_SIZE = 1;
	public static final int RTMP_MSG_CONTROL_TYPE_ABORT = 2;
	public static final int RTMP_MSG_CONTROL_TYPE_ACKNOWLEDGEMENT = 3; // bytes read report
	public static final int RTMP_MSG_CONTROL_TYPE_WINDOW_ACKNOWLEDGEMENT_SIZE = 5; // server bandwidth
	public static final int RTMP_MSG_CONTROL_TYPE_SET_PEER_BANDWIDTH = 6; // client bandwidth

	/* User Control Messages Event (4) */
	public static final int RTMP_MSG_USER_CONTROL_TYPE_EVENT = 4;

	public static final int RTMP_MSG_USER_CONTROL_TYPE_AUDIO = 8; // message.header.type =8 헤더에 오디오 타입임이 정의됨
	public static final int RTMP_MSG_USER_CONTROL_TYPE_VIDEO = 9; // message.header.type =8 헤더에 비디오 타입임이 정의됨

	/** Data Message 타입
	 *  서버와 클라이언트가 메타데이터를 통신하기위한 메세지 타입,
	 *  메타데이터에는 생성 시간, 기간, 테마 등과 같은 데이터(오디오, 비디오 등)에 대한 세부 정보가 포함됩니다.
	 *  이러한 메시지에는 AMF0
	 */
	public static final int RTMP_MSG_DATA_TYPE_AMF3 = 15; // AMF3
	public static final int RTMP_MSG_DATA_TYPE_AMF0 = 18; // AMF0

	/**
	 * Shared Object 타입
	 * RTMP에서 공유 객체 메시지는 주로 서로 다른 Flash 애플리케이션 간의 상호작용을 가능하게 하는데 사용됨
	 * 이를 통해 여러 Flash 애플리케이션에서 데이터를 교환하고 상태를 공유할 수 있다.
	 * 쉽게 생각하면 아자르, 페이스톡과 같이 영상을 저장하지 않고 실시간 스트리밍 할 때 이용한다.
	 * 그런데, 현재 RTMP 프로토콜로 전송되는 영상을 브라우저에서 재생하는 Adobe Flash가 지원종료되어서 사용되지 않음
	 *
	 * 공유 객체 메시지는 다음과 같은 상황에서 사용될 수 있습니다:
	 */
	public static final int RTMP_MSG_SHARED_OBJ_TYPE_AMF3 = 16; // AMF3
	public static final int RTMP_MSG_SHARED_OBJ_TYPE_AMF0 = 19; // AMF0

	/** Data Message 타입
	 *  클라이언트와 서버 간에 AMF 인코딩 명령을 전달합니다.   연결, createStream, 게시, 재생, 일시 중지와 같은 일부 작업을 수행하기 위해 전송됩니다.
	 * 명령 메시지는 명령 이름, 트랜잭션 ID, 관련 매개변수가 포함된 명령 개체로 구성됩니다
	 */
	public static final int RTMP_MSG_COMMAND_TYPE_AMF3 = 17; // AMF3
	public static final int RTMP_MSG_COMMAND_TYPE_AMF0 = 20; // AMF0

	/* Stream status */
	public static final int STREAM_BEGIN = 0x00;
	public static final int STREAM_EOF = 0x01;
	public static final int STREAM_DRY = 0x02;
	public static final int STREAM_EMPTY = 0x1f;
	public static final int STREAM_READY = 0x20;

}