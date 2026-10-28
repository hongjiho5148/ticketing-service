package com.ticketing.backend.queue;

import java.io.IOException;

/** Where a waiting client's updates go. The SSE connection in production; a recording fake in tests. */
interface QueueStreamSink {

    void send(String event, Object data) throws IOException;

    /** A line the client ignores, sent so idle connections are not closed by whatever sits between us and the browser. */
    void heartbeat() throws IOException;

    void complete();
}
