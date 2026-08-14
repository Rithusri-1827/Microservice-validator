package com.msval.governance.gateway;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.Semaphore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.msval.governance.config.MsvalProperties;
import com.msval.governance.orchestrate.IntakeService;
import com.msval.governance.persist.Jsonb;

/**
 * DD-014 / W3 — the IF-008 endpoint: accept ≤ 4 connections (single ingress expected),
 * read W1 frames, dedup on event_id (duplicate ⇒ ACK only), blocking queue.put =
 * backpressure (reader blocked ⇒ TCP window closes), then ACK {event_id, ok}.
 */
@Component
public class GatewayServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GatewayServer.class);
    private static final int MAX_CONNECTIONS = 4;

    private final MsvalProperties cfg;
    private final IntakeService intake;
    private final DedupWindow dedup;
    private final Semaphore connections = new Semaphore(MAX_CONNECTIONS);

    private volatile boolean running;
    private ServerSocket server;

    public GatewayServer(MsvalProperties cfg, IntakeService intake) {
        this.cfg = cfg;
        this.intake = intake;
        this.dedup = new DedupWindow(cfg.dedupWindowS() * 1000L);
    }

    @Override
    public void start() {
        try {
            server = new ServerSocket(cfg.gatewayPort());
        } catch (IOException e) {
            throw new IllegalStateException("cannot bind gateway port " + cfg.gatewayPort(), e);
        }
        running = true;
        Thread acceptor = new Thread(this::acceptLoop, "msval-gw-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
        log.info("gateway listening on :{}", cfg.gatewayPort());
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = server.accept();
                if (!connections.tryAcquire()) {
                    socket.close(); // DD-014: accept ≤ 4
                    continue;
                }
                Thread reader = new Thread(() -> readLoop(socket), "msval-gw-reader");
                reader.setDaemon(true);
                reader.start();
            } catch (IOException e) {
                if (running) {
                    log.warn("gateway accept failed: {}", e.getMessage());
                }
            }
        }
    }

    private void readLoop(Socket socket) {
        try (socket) {
            OutputStream out = socket.getOutputStream();
            while (running && !socket.isClosed()) {
                JsonNode frame;
                try {
                    frame = FrameCodec.read(socket.getInputStream());
                } catch (FrameCodec.FrameSizeException e) {
                    nack(out, "", "FRAME_SIZE");
                    return; // W1: oversize ⇒ NACK then close
                } catch (FrameCodec.FrameParseException e) {
                    nack(out, "", "FRAME_PARSE");
                    continue; // W1: parse failure ⇒ NACK, keep connection
                } catch (EOFException e) {
                    return; // peer gone (incl. mid-frame disconnect)
                }
                handle(frame, out);
            }
        } catch (IOException e) {
            log.debug("gateway connection closed: {}", e.getMessage());
        } finally {
            connections.release();
        }
    }

    private void handle(JsonNode frame, OutputStream out) throws IOException {
        // W3 envelope: {envelope: <IF-007 payload>, cdm, intake_checks, ingress_bundle_version, …}
        String eventId = frame.path("envelope").path("event_id").asText("");
        if (eventId.isEmpty()) {
            eventId = frame.path("event_id").asText(""); // lenient: bare IF-007 payloads
        }
        if (eventId.isEmpty()) {
            nack(out, "", "MISSING_EVENT_ID");
            return;
        }
        if (!dedup.checkAndPut(eventId)) {
            ack(out, eventId); // duplicate within window ⇒ ACK only
            return;
        }
        try {
            intake.submit(frame); // blocking put — ADR-006 backpressure
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            nack(out, eventId, "SHUTDOWN");
            return;
        }
        ack(out, eventId);
    }

    private static void ack(OutputStream out, String eventId) throws IOException {
        ObjectNode resp = Jsonb.MAPPER.createObjectNode();
        resp.put("event_id", eventId);
        resp.put("ok", true);
        FrameCodec.write(out, resp);
    }

    private static void nack(OutputStream out, String eventId, String error) throws IOException {
        ObjectNode resp = Jsonb.MAPPER.createObjectNode();
        resp.put("event_id", eventId);
        resp.put("ok", false);
        resp.putArray("errors").add(error);
        FrameCodec.write(out, resp);
    }

    @Override
    public void stop() {
        running = false;
        try {
            if (server != null) {
                server.close();
            }
        } catch (IOException ignored) {
            // shutting down
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
