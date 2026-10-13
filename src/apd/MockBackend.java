package apd;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Local HTTP backends for routing filters. The real routing filter and the
 * native HTTP client make a real connection: hostnames in the mocked URLs are
 * mapped to loopback addresses through the private /etc/hosts the launcher
 * bind-mounts for this process (APD_HOSTS_FILE).
 */
final class MockBackend implements AutoCloseable {

    /** One canned response. url may contain * wildcards; method defaults to any. */
    public static final class Route {
        public String url;
        public String method;
        public int status = 200;
        public Map<String, String> headers = new LinkedHashMap<>();
        public String body;
        public String bodyFile;
        public long delayMs;
        /** Simulated transport failure: refused (connection failed) or timeout (504 + abort). */
        public String error;
        /** HTTP reason phrase; defaults to the standard text for the status. */
        public String reason;
        transient Pattern pathPattern;
        transient String hostKey;
    }

    /** A request the gateway sent to a mock. */
    public static final class Call {
        public String method;
        public String url;
        public Map<String, List<String>> headers = new LinkedHashMap<>();
        public String body;
        public int status;
    }

    final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
    private final List<HttpServer> servers = new ArrayList<>();
    private final Map<String, String> hostIps = new LinkedHashMap<>();
    private final Path hostsFile;
    private final String originalHosts;

    MockBackend(List<Route> routes, File baseDir) throws IOException {
        String hf = System.getenv("APD_HOSTS_FILE");
        this.hostsFile = hf == null ? null : new File(hf).toPath();
        this.originalHosts = hostsFile == null ? null : Files.readString(hostsFile);
        Map<String, List<Route>> byEndpoint = new LinkedHashMap<>();
        int nextIp = 2;
        for (Route r : routes) {
            URI u = URI.create(r.url.replace("*", "__STAR__"));
            String scheme = u.getScheme() == null ? "http" : u.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http")) {
                throw new IllegalArgumentException("mock " + r.url + ": only http:// backends can be mocked");
            }
            String host = u.getHost();
            int port = u.getPort() == -1 ? 80 : u.getPort();
            String ip = host.equals("localhost") || host.startsWith("127.") ? host : hostIps.get(host);
            if (ip == null) {
                ip = "127.0.0." + nextIp++;
                hostIps.put(host, ip);
            }
            String path = (u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath()).replace("__STAR__", "*");
            String query = u.getRawQuery() == null ? "" : "?" + u.getRawQuery().replace("__STAR__", "*");
            r.pathPattern = Debugger.glob(path + query);
            r.hostKey = ip + ":" + port;
            if (r.bodyFile != null) {
                File f = new File(r.bodyFile);
                r.body = Files.readString((f.isAbsolute() || baseDir == null ? f : new File(baseDir, r.bodyFile)).toPath());
            }
            byEndpoint.computeIfAbsent(r.hostKey, k -> new ArrayList<>()).add(r);
        }
        if (!hostIps.isEmpty()) {
            if (hostsFile == null) {
                throw new IllegalStateException("mocks for named hosts " + hostIps.keySet()
                        + " need the launcher's private /etc/hosts (run through bin/apimgr-policy-debugger as root)");
            }
            StringBuilder sb = new StringBuilder(originalHosts).append("\n# apimgr-policy-debugger mocks\n");
            hostIps.forEach((h, ip) -> sb.append(ip).append(' ').append(h).append('\n'));
            Files.writeString(hostsFile, sb.toString(), StandardOpenOption.TRUNCATE_EXISTING);
        }
        for (Map.Entry<String, List<Route>> ep : byEndpoint.entrySet()) {
            String[] hp = ep.getKey().split(":");
            InetAddress addr = InetAddress.getByName(hp[0]);
            HttpServer server = HttpServer.create(new InetSocketAddress(addr, Integer.parseInt(hp[1])), 64);
            List<Route> list = ep.getValue();
            server.createContext("/", ex -> handle(ex, list));
            server.start();
            servers.add(server);
        }
    }

    private void handle(HttpExchange ex, List<Route> routes) throws IOException {
        Call call = new Call();
        call.method = ex.getRequestMethod();
        call.url = ex.getRequestHeaders().getFirst("Host") + ex.getRequestURI();
        ex.getRequestHeaders().forEach((k, v) -> call.headers.put(k, v));
        try (InputStream in = ex.getRequestBody()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            in.transferTo(bo);
            call.body = bo.toString(StandardCharsets.UTF_8);
        }
        String target = ex.getRequestURI().getRawPath() + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery());
        Route match = null;
        for (Route r : routes) {
            if ((r.method == null || r.method.equalsIgnoreCase(call.method))
                    && (r.pathPattern.matcher(target).matches() || r.pathPattern.matcher(ex.getRequestURI().getRawPath()).matches())) {
                match = r;
                break;
            }
        }
        byte[] body;
        if (match == null) {
            call.status = 404;
            body = ("apimgr-policy-debugger mock: no route for " + call.method + " " + target).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/plain");
        } else {
            if (match.delayMs > 0) {
                try {
                    Thread.sleep(match.delayMs);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            call.status = match.status;
            body = match.body == null ? new byte[0] : match.body.getBytes(StandardCharsets.UTF_8);
            match.headers.forEach((k, v) -> ex.getResponseHeaders().add(k, v));
        }
        calls.add(call);
        ex.sendResponseHeaders(call.status, body.length == 0 ? -1 : body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    @Override
    public void close() {
        for (HttpServer s : servers) {
            s.stop(0);
        }
        if (hostsFile != null && !hostIps.isEmpty()) {
            try {
                Files.writeString(hostsFile, originalHosts, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException ignored) {
                // private mount namespace: discarded with the process anyway
            }
        }
    }
}
