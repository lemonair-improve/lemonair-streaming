package com.hanghae.lemonairstreaming.rmtp.model.messages;

public class RtmpConstants {

	public static final byte RTMP_VERSION = 3;
	public static final int RTMP_HANDSHAKE_SIZE = 1536;
	public static final int RTMP_HANDSHAKE_VERSION_LENGTH = 1;
	public static final int RTMP_MAX_TIMESTAMP = 0XFFFFFF;
	public static final int RTMP_DEFAULT_CHUNK_SIZE = 128;

	public static final int RTMP_DEFAULT_OUTPUT_ACK_SIZE = 5_000_000;
	public static final int RTMP_DEFAULT_OUTPUT_CHUNK_SIZE = 5000;
	public static final int RTMP_DEFAULT_MESSAGE_STREAM_ID_VALUE = 42;

	public static final int RTMP_CHUNK_TYPE_0 = 0;
	public static final int RTMP_CHUNK_TYPE_1 = 1;
	public static final int RTMP_CHUNK_TYPE_2 = 2;

	public static final int RTMP_CHUNK_TYPE_3 = 3;

	/* Protocol Control Messages */
	public static final int RTMP_MSG_CONTROL_TYPE_SET_CHUNK_SIZE = 1;
	public static final int RTMP_MSG_CONTROL_TYPE_ABORT = 2;
	public static final int RTMP_MSG_CONTROL_TYPE_ACKNOWLEDGEMENT = 3;
	public static final int RTMP_MSG_CONTROL_TYPE_WINDOW_ACKNOWLEDGEMENT_SIZE = 5;
	public static final int RTMP_MSG_CONTROL_TYPE_SET_PEER_BANDWIDTH = 6;

	/* User Control Messages Event (4) */
	public static final int RTMP_MSG_USER_CONTROL_TYPE_EVENT = 4;

	public static final int RTMP_MSG_USER_CONTROL_TYPE_AUDIO = 8;
	public static final int RTMP_MSG_USER_CONTROL_TYPE_VIDEO = 9;

	public static final int RTMP_MSG_DATA_TYPE_AMF3 = 15;
	public static final int RTMP_MSG_DATA_TYPE_AMF0 = 18;

	public static final int RTMP_MSG_SHARED_OBJ_TYPE_AMF3 = 16;
	public static final int RTMP_MSG_SHARED_OBJ_TYPE_AMF0 = 19;

	public static final int RTMP_MSG_COMMAND_TYPE_AMF3 = 17;
	public static final int RTMP_MSG_COMMAND_TYPE_AMF0 = 20;

	/* Stream status */
	public static final int STREAM_BEGIN = 0x00;
	public static final int STREAM_EOF = 0x01;
	public static final int STREAM_DRY = 0x02;
	public static final int STREAM_EMPTY = 0x1f;
	public static final int STREAM_READY = 0x20;

}