package com.msval.governance.gateway;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.msval.governance.config.MsvalProperties;
import com.msval.governance.persist.Jsonb;
import com.msval.governance.persist.ViolationRepo;
import com.msval.governance.registry.RuleCache;

/**
 * DD-014 / W4 — IF-009 alert fan-out. On connect the server sends the snapshot frame,
 * then pushes alert frames. Per subscriber: ArrayBlockingQueue(500), drop-oldest with a
 * dropped counter, and a writer thread that coalesces up to 50 frames per batch on the
 * ALERT_BATCH_MS cadence, prepending {type:"dropped", count} after overflow.
 */
@Component
public class AlertBroadcaster implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AlertBroadcaster.class);
    private static final int SUBSCRIBER_QUEUE = 500;
    private static final int BATCH_MAX = 50;

    private final MsvalProperties cfg;
    private final RuleCache ruleCache;
    private final ViolationRepo violations;

    private volatile boolean running;
    private ServerSocket server;
    private Thread acceptor;
    private final Map<Long, Subscriber> subscribers = new ConcurrentHashMap<>();
    private long nextId;

    public AlertBroadcaster(MsvalProperties cfg, RuleCache ruleCache, ViolationRepo violations) {
        this.cfg = cfg;
        this.ruleCache = ruleCache;
        this.violations = violations;
    }

    // ------------------------------------------------------------ emit surface

    /** Build + broadcast one W4 alert frame. Kinds: VIOLATION|WARN|WAIVER_EXPIRED|DRIFT|RECON|SYSTEM. */
    public void emit(String kind, String serviceId, String environment, String ruleId,
            String severity, String message) {
        ObjectNode frame = Jsonb.MAPPER.createObjectNode();
        frame.put("type", "alert");
        frame.put("event_id", UUID.randomUUID().toString());
        frame.put("kind", kind);
        if (serviceId != null) {
            frame.put("service_id", serviceId);
        }
        if (environment != null) {
            frame.put("environment", environment);
        }
        if (ruleId != null) {
            frame.put("rule_id", ruleId);
        }
        if (severity != null) {
            frame.put("severity", severity);
        }
        frame.put("message", message);
        frame.put("ts", Instant.now().toString());
        broadcast(frame);
    }

    private void broadcast(JsonNode frame) {
        for (Subscriber s : subscribers.values()) {
            s.offer(frame);
        }
    }

    // --------------------------------------------------------------- lifecycle

    @Override
    public void start() {
        try {
            server = new ServerSocket(cfg.alertPort());
        } catch (IOException e) {
            throw new IllegalStateException("cannot bind alert port " + cfg.alertPort(), e);
        }
        running = true;
        acceptor = new Thread(this::acceptLoop, "msval-alert-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
        log.info("alert stream listening on :{}", cfg.alertPort());
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = server.accept();
                long id;
                synchronized (this) {
                    id = ++nextId;
                }
                Subscriber sub = new Subscriber(id, socket);
                subscribers.put(id, sub);
                sub.startThreads();
            } catch (IOException e) {
                if (running) {
                    log.warn("alert accept failed: {}", e.getMessage());
                }
            }
        }
    }

    private JsonNode snapshotFrame() {
        ObjectNode snap = Jsonb.MAPPER.createObjectNode();
        snap.put("type", "snapshot");
        ObjectNode active = snap.putObject("active_bundles");
        ruleCache.activeVersions().forEach(active::put);
        ObjectNode counts = snap.putObject("open_counts");
        try {
            violations.openCounts().forEach((env, byStatus) -> {
                ObjectNode e = counts.putObject(env);
                byStatus.forEach(e::put);
            });
        } catch (Exception e) {
            log.warn("snapshot counts unavailable: {}", e.getMessage()); // DB down: snapshot degrades
        }
        return snap;
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
        subscribers.values().forEach(Subscriber::close);
        subscribers.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    // -------------------------------------------------------------- subscriber

    private final class Subscriber {
        private final long id;
        private final Socket socket;
        private final ArrayBlockingQueue<JsonNode> queue = new ArrayBlockingQueue<>(SUBSCRIBER_QUEUE);
        private final AtomicInteger dropped = new AtomicInteger();

        Subscriber(long id, Socket socket) {
            this.id = id;
            this.socket = socket;
        }

        void startThreads() {
            Thread writer = new Thread(this::writeLoop, "msval-alert-writer-" + id);
            writer.setDaemon(true);
            writer.start();
            Thread reader = new Thread(this::readLoop, "msval-alert-reader-" + id);
            reader.setDaemon(true);
            reader.start();
        }

        /** Drop-oldest on overflow (DD-014: poll(); offer(); dropped++). */
        void offer(JsonNode frame) {
            while (!queue.offer(frame)) {
                queue.poll();
                dropped.incrementAndGet();
            }
        }

        private void writeLoop() {
            try {
                FrameCodec.write(socket.getOutputStream(), snapshotFrame()); // W4: snapshot first
                while (running && !socket.isClosed()) {
                    JsonNode first = queue.poll(cfg.alertBatchMs(), TimeUnit.MILLISECONDS);
                    if (first == null) {
                        continue;
                    }
                    int d = dropped.getAndSet(0); // reset per batch (DD-014)
                    if (d > 0) {
                        ObjectNode droppedFrame = Jsonb.MAPPER.createObjectNode();
                        droppedFrame.put("type", "dropped");
                        droppedFrame.put("count", d);
                        FrameCodec.write(socket.getOutputStream(), droppedFrame);
                    }
                    FrameCodec.write(socket.getOutputStream(), first);
                    for (int i = 1; i < BATCH_MAX; i++) {
                        JsonNode next = queue.poll();
                        if (next == null) {
                            break;
                        }
                        FrameCodec.write(socket.getOutputStream(), next);
                    }
                }
            } catch (IOException | InterruptedException e) {
                // peer gone or shutdown
            } finally {
                close();
            }
        }

        /** W4: nothing after subscribe — read (and ignore) the subscribe frame, detect EOF. */
        private void readLoop() {
            try {
                while (running && !socket.isClosed()) {
                    FrameCodec.read(socket.getInputStream());
                }
            } catch (IOException e) {
                close();
            }
        }

        void close() {
            subscribers.remove(id);
            try {
                socket.close();
            } catch (IOException ignored) {
                // already closed
            }
        }
    }
}
