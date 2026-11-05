package com.topstep.trading.connector;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AGENT-05.12 test helper: a local fake TopstepX gateway (JDK HttpServer on
 * 127.0.0.1, no network). Contract/search answers per searchText; bar
 * requests are served from a scripted queue of (status, Retry-After, body).
 */
final class FakeTopstepX implements AutoCloseable {

    record Scripted(int status, String retryAfter, String body) {}

    record Call(String path, String body) {}

    private final HttpServer server;
    /** searchText -> Contract/search response body. Missing = empty contracts list. */
    final Map<String, String> contractSearch = new ConcurrentHashMap<>();
    /** Fixed responses by path. */
    final Map<String, String> responses = new ConcurrentHashMap<>();
    /** Scripted History/retrieveBars responses; when empty, {@link #defaultBars} is served. */
    final Deque<Scripted> bars = new ArrayDeque<>();
    volatile Scripted defaultBars = new Scripted(200, null, "{\"bars\":[],\"success\":true,\"errorCode\":0}");
    final List<Call> calls = new CopyOnWriteArrayList<>();

    private static final Pattern SEARCH_TEXT = Pattern.compile("\"searchText\"\\s*:\\s*\"([^\"]*)\"");

    FakeTopstepX() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        responses.put("/api/Account/search",
                "{\"accounts\":[{\"id\":28042793,\"name\":\"PRAC-TEST\",\"canTrade\":true,\"simulated\":true}],\"success\":true}");
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            String reqBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            calls.add(new Call(path, reqBody));
            int status = 200;
            String retryAfter = null;
            String body;
            if (path.equals("/api/Contract/search")) {
                Matcher m = SEARCH_TEXT.matcher(reqBody);
                String text = m.find() ? m.group(1) : "";
                body = contractSearch.getOrDefault(text, "{\"contracts\":[],\"success\":true,\"errorCode\":0}");
            } else if (path.equals("/api/History/retrieveBars")) {
                Scripted s;
                synchronized (bars) {
                    s = bars.isEmpty() ? defaultBars : bars.poll();
                }
                status = s.status();
                retryAfter = s.retryAfter();
                body = s.body();
            } else {
                body = responses.getOrDefault(path, "{\"success\":false,\"errorCode\":99}");
            }
            if (retryAfter != null) {
                ex.getResponseHeaders().add("Retry-After", retryAfter);
            }
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        });
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    TopstepConnector connector() {
        return new TopstepConnector(url(), "u", "k", "PRAC-TEST", false);
    }

    long count(String path) {
        return calls.stream().filter(c -> c.path().equals(path)).count();
    }

    List<Call> callsTo(String path) {
        return calls.stream().filter(c -> c.path().equals(path)).toList();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
