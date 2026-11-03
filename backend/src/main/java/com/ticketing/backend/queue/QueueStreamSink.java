package com.ticketing.backend.queue;

import java.io.IOException;

/** 기다리는 클라이언트에게 가는 업데이트의 도착지다. 운영에서는 SSE 연결이고 테스트에서는 기록하는 가짜다. */
interface QueueStreamSink {

    void send(String event, Object data) throws IOException;

    /** 클라이언트가 무시하는 줄이다. 우리와 브라우저 사이에 있는 무엇이 유휴 연결을 닫지 않도록 보낸다. */
    void heartbeat() throws IOException;

    void complete();
}
