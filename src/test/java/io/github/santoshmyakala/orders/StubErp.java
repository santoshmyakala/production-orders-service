package io.github.santoshmyakala.orders;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/** A real HTTP server standing in for the ERP, so timeouts, retries and the circuit breaker run for real. */
public final class StubErp {

    public enum Mode { OK, INSUFFICIENT, DOWN, SLOW }

    public static final AtomicReference<Mode> MODE = new AtomicReference<>(Mode.OK);
    /** Idempotency-Key header of every call received. */
    public static final List<String> CALLS = new CopyOnWriteArrayList<>();
    private static HttpServer server;

    private StubErp() {}

    public static synchronized String start() throws IOException {
        if (server == null) {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/reservations", exchange -> {
                CALLS.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                exchange.getRequestBody().readAllBytes();
                Mode mode = MODE.get();
                if (mode == Mode.SLOW) {
                    try {
                        Thread.sleep(1_500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                int status = switch (mode) {
                    case OK, SLOW -> 200;
                    case INSUFFICIENT -> 409;
                    case DOWN -> 503;
                };
                byte[] body = (status == 200
                        ? "{\"reservationId\":\"RES-" + CALLS.size() + "\"}"
                        : "{\"error\":\"" + mode + "\"}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                try {
                    exchange.sendResponseHeaders(status, body.length);
                    exchange.getResponseBody().write(body);
                } catch (IOException ignored) {
                    // the client gave up (read timeout)
                }
                exchange.close();
            });
            server.start();
        }
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public static void reset() {
        MODE.set(Mode.OK);
        CALLS.clear();
    }
}
