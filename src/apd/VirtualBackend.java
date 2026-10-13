package apd;

import com.vordel.circuit.CircuitAbortException;
import com.vordel.circuit.CircuitDelegateProcessor;
import com.vordel.circuit.DelegatingProcessor;
import com.vordel.circuit.Message;
import com.vordel.circuit.net.ConnectToURLProcessor;
import com.vordel.circuit.net.ConnectionProcessor;
import com.vordel.config.Circuit;
import com.vordel.dwe.ByteArrayContentSource;
import com.vordel.dwe.http.Response;
import com.vordel.el.Selector;
import com.vordel.mime.Body;
import com.vordel.mime.HeaderSet;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Sandbox replacement for the network leg of routing filters
 * (ConnectionProcessor and ConnectToURLProcessor). Everything before and after
 * the wire is the product's own code: destination and request are evaluated
 * with the filter's configured selectors, and the reply is written into the
 * message by com.vordel.dwe.http.Response.buildReply, as a real response would.
 */
final class VirtualBackend {

    final List<MockBackend.Call> calls = Collections.synchronizedList(new ArrayList<>());
    private final List<Compiled> routes = new ArrayList<>();

    private static final class Compiled {
        MockBackend.Route route;
        Pattern host;
        Integer port;
        Pattern target;
        String scheme;
    }

    VirtualBackend(List<MockBackend.Route> mocks, File baseDir) throws Exception {
        if (mocks == null) {
            return;
        }
        for (MockBackend.Route r : mocks) {
            URI u = URI.create(r.url.replace("*", "__STAR__"));
            Compiled c = new Compiled();
            c.route = r;
            c.scheme = u.getScheme() == null ? null : u.getScheme().toLowerCase(Locale.ROOT);
            c.host = Debugger.glob(u.getHost() == null ? "*" : u.getHost().replace("__STAR__", "*"));
            c.port = u.getPort() == -1 ? null : u.getPort();
            String path = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
            String query = u.getRawQuery() == null ? "" : "?" + u.getRawQuery();
            c.target = Debugger.glob((path + query).replace("__STAR__", "*"));
            if (r.bodyFile != null) {
                File f = new File(r.bodyFile);
                r.body = Files.readString((f.isAbsolute() || baseDir == null ? f : new File(baseDir, r.bodyFile)).toPath());
            }
            routes.add(c);
        }
    }

    boolean handles(Object processor) {
        return processor instanceof ConnectionProcessor;
    }

    /** Mirrors ConnectToURLProcessor.invoke / ConnectionProcessor.invoke with the transaction served from the mocks. */
    boolean invoke(Circuit c, ConnectionProcessor p, Message m) throws CircuitAbortException {
        String method;
        HeaderSet headers;
        Body body;
        URL url;
        boolean connectToUrl = p instanceof ConnectToURLProcessor;
        try {
            if (connectToUrl) {
                URI conuri = (URI) selector(p, "uri").substitute(m);
                url = conuri.toURL();
                method = (String) selector(p, "verb").substitute(m);
                Selector<?> hs = selector(p, "protoHeaders");
                headers = hs == null ? new HeaderSet() : (HeaderSet) hs.substitute(m);
                Selector<?> bs = selector(p, "body");
                body = method == null || method.equals("GET") || bs == null ? null : (Body) bs.substitute(m);
                m.get("http.request.url");
                m.put("http.request.uri", conuri);
                m.put("http.request.path", url.getPath());
                m.put("http.destination.host", url.getHost());
                m.put("http.destination.port", Integer.toString(url.getPort() == -1 ? url.getDefaultPort() : url.getPort()));
                m.put("http.destination.protocol", url.getProtocol());
            } else {
                headers = (HeaderSet) m.get("http.headers");
                body = (Body) m.get("content.body");
                method = (String) m.get("http.request.verb");
                URI uri = (URI) m.get("http.request.uri");
                String proto = String.valueOf(m.get("http.destination.protocol"));
                String host = String.valueOf(m.get("http.destination.host"));
                String port = String.valueOf(m.get("http.destination.port"));
                url = new URL(proto + "://" + host + ":" + port + (uri == null ? "/" : uri.getRawPath()
                        + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())));
            }
        } catch (CircuitAbortException e) {
            throw e;
        } catch (Exception e) {
            throw new CircuitAbortException(e);
        }

        long start = System.currentTimeMillis();
        m.remove("http.response.status");
        m.remove("http.response.info");
        MockBackend.Call call = new MockBackend.Call();
        call.method = method;
        call.url = url.getHost() + (url.getPort() == -1 ? "" : ":" + url.getPort()) + url.getFile();
        if (headers != null) {
            call.headers.putAll(ExecutionTrace.headers(headers));
        }
        call.body = body == null ? null : Lifecycle.readBody(body, 1 << 20);
        calls.add(call);

        Compiled match = find(method, url);
        String result = "SUCCESS";
        String failure = null;
        try {
            if (match == null || "refused".equalsIgnoreCase(match.route.error)) {
                call.status = 0;
                result = "ERROR_CONNECTION_FAILED";
                failure = match == null
                        ? "apimgr-policy-debugger sandbox: no virtual backend for " + method + " " + url + " (add a mock)"
                        : "Connection refused (virtual backend)";
                runConnectionFailurePolicy(c, p, m);
                return false;
            }
            MockBackend.Route r = match.route;
            if (r.delayMs > 0) {
                Thread.sleep(r.delayMs);
            }
            if ("timeout".equalsIgnoreCase(r.error)) {
                call.status = 504;
                result = "ERROR_TIMEOUT";
                failure = "Timeout while waiting for read.";
                m.put("http.response.status", 504);
                m.put("http.response.info", failure);
                throw new CircuitAbortException(failure, null, 5);
            }
            call.status = r.status;
            reply(r, m, method);
            return true;
        } catch (CircuitAbortException e) {
            if (result.equals("SUCCESS")) {
                result = "ERROR_CIRCUIT_ABORT";
                failure = e.getMessage();
            }
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CircuitAbortException(e);
        } catch (Exception e) {
            result = "ERROR_UNKNOWN";
            failure = e.getMessage();
            throw new CircuitAbortException(e);
        } finally {
            m.put("filter.connect.to.url.result", processorResult(result));
            if (!result.equals("SUCCESS")) {
                m.put("filter.connect.to.url.failure.description", failure);
            }
            if (connectToUrl) {
                m.put("http.response.time", (System.currentTimeMillis() - start) + "ms");
            }
        }
    }

    private Compiled find(String method, URL url) {
        int port = url.getPort() == -1 ? url.getDefaultPort() : url.getPort();
        String target = url.getFile().isEmpty() ? "/" : url.getFile();
        for (Compiled c : routes) {
            if (c.route.method != null && !c.route.method.equalsIgnoreCase(method)) {
                continue;
            }
            if (c.scheme != null && !c.scheme.equalsIgnoreCase(url.getProtocol())) {
                continue;
            }
            if (!c.host.matcher(url.getHost()).matches()) {
                continue;
            }
            if (c.port != null && c.port != port) {
                continue;
            }
            if (c.target.matcher(target).matches() || c.target.matcher(url.getPath()).matches()) {
                return c;
            }
        }
        return null;
    }

    /** Builds a real Response object and lets the product write it into the message. */
    private static void reply(MockBackend.Route r, Message m, String method) throws Exception {
        byte[] payload = r.body == null ? new byte[0] : r.body.getBytes(StandardCharsets.UTF_8);
        HeaderSet hs = new HeaderSet();
        boolean hasType = false;
        for (Map.Entry<String, String> h : r.headers.entrySet()) {
            hs.addHeader(h.getKey(), h.getValue());
            hasType |= h.getKey().equalsIgnoreCase("Content-Type");
        }
        if (!hasType && payload.length > 0) {
            hs.addHeader("Content-Type", "text/plain");
        }
        if (hs.getHeader("Content-Length") == null) {
            hs.addHeader("Content-Length", String.valueOf(payload.length));
        }
        Response resp = new Response();
        resp.setCode(r.status);
        resp.setText(r.reason != null ? r.reason : reason(r.status));
        Field headers = Response.class.getDeclaredField("headers");
        headers.setAccessible(true);
        headers.set(resp, hs);
        if (payload.length > 0 || !"HEAD".equals(method)) {
            resp.setContent(new ByteArrayContentSource(payload));
        }
        resp.buildReply(m, method);
    }

    private static void runConnectionFailurePolicy(Circuit c, ConnectionProcessor p, Message m) {
        try {
            Object settings = field(ConnectionProcessor.class, p, "connectionSettings");
            Object failureCircuit = field(ConnectionProcessor.class, p, "connectionFailureCircuit");
            if (settings == null || failureCircuit == null) {
                return;
            }
            Selector<?> run = (Selector<?>) settings.getClass().getMethod("isRunPolicyOnConnectionFailure").invoke(settings);
            if (Boolean.TRUE.equals(run.substitute(m))) {
                Method call = DelegatingProcessor.class.getDeclaredMethod("callCircuit", Circuit.class, Message.class, CircuitDelegateProcessor.class);
                call.setAccessible(true);
                call.invoke(p, c, m, failureCircuit);
            }
        } catch (Exception e) {
            System.err.println("apimgr-policy-debugger: connection failure policy not run: " + GatewayRuntime.describe(e));
        }
    }

    private static Selector<?> selector(Object p, String name) throws Exception {
        return (Selector<?>) field(ConnectToURLProcessor.class, p, name);
    }

    private static Object field(Class<?> owner, Object target, String name) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object processorResult(String name) {
        try {
            Class<? extends Enum> e = (Class<? extends Enum>) Class.forName("com.vordel.circuit.net.ConnectionProcessor$ProcessorResult");
            return Enum.valueOf(e, name);
        } catch (Exception ex) {
            return name;
        }
    }

    private static final Map<Integer, String> REASONS = new HashMap<>();

    static {
        String[] pairs = {"200", "OK", "201", "Created", "202", "Accepted", "204", "No Content", "301", "Moved Permanently",
                "302", "Found", "304", "Not Modified", "400", "Bad Request", "401", "Unauthorized", "403", "Forbidden",
                "404", "Not Found", "405", "Method Not Allowed", "409", "Conflict", "415", "Unsupported Media Type",
                "422", "Unprocessable Entity", "429", "Too Many Requests", "500", "Internal Server Error",
                "502", "Bad Gateway", "503", "Service Unavailable", "504", "Gateway Timeout"};
        for (int i = 0; i < pairs.length; i += 2) {
            REASONS.put(Integer.valueOf(pairs[i]), pairs[i + 1]);
        }
    }

    static String reason(int status) {
        return REASONS.getOrDefault(status, "Status " + status);
    }
}
