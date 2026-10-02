package com.cardflow.authorization.fraud;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

/** A stand-in for fraud-service on a random local port, built on the JDK's HTTP server (no extra dependency). */
final class FakeFraudService {

    enum Mode { RESPOND, ERROR, SLOW }

    final HttpServer server;
    final AtomicInteger calls = new AtomicInteger();
    final List<String> bodies = new CopyOnWriteArrayList<>();
    final List<String> correlationIds = new CopyOnWriteArrayList<>();
    volatile Mode mode = Mode.RESPOND;
    volatile String band = "LOW";
    volatile double score = 0.01;
    volatile long slowMillis = 1_500;

    FakeFraudService() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/score", exchange -> {
            calls.incrementAndGet();
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String cid = exchange.getRequestHeaders().getFirst("X-Correlation-Id");
            if (cid != null) {
                correlationIds.add(cid);
            }
            try {
                if (mode == Mode.SLOW) {
                    Thread.sleep(slowMillis);
                }
                if (mode == Mode.ERROR) {
                    exchange.sendResponseHeaders(500, -1);
                    return;
                }
                byte[] json = """
                        {"requestId": "x", "score": %s, "band": "%s", "modelVersion": "fraud-xgb-test",
                         "reasons": [{"code": "HIGH_VELOCITY", "description": "Many transactions", "contribution": 1.7}]}"""
                        .formatted(score, band).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, json.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(json);
                }
            } catch (InterruptedException | IOException e) {
                // client gave up (timeout); nothing to do
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void reset() {
        mode = Mode.RESPOND;
        band = "LOW";
        score = 0.01;
        calls.set(0);
        bodies.clear();
        correlationIds.clear();
    }
}
