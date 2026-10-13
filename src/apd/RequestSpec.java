package apd;

import com.vordel.circuit.Message;
import com.vordel.dwe.ByteArrayContentSource;
import com.vordel.dwe.CorrelationID;
import com.vordel.mime.Body;
import com.vordel.mime.ContentType;
import com.vordel.mime.HeaderSet;

import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/** An inbound HTTP request described in a test case or on the command line. */
public final class RequestSpec {
    public String method = "GET";
    public String path = "/";
    public String host = "localhost";
    public String clientIp = "127.0.0.1";
    public Map<String, Object> headers = new LinkedHashMap<>();
    public String body;
    public String bodyFile;
    public String contentType;
    /** Extra message attributes set before the lifecycle starts (e.g. authentication.subject.id). */
    public Map<String, Object> attributes = new LinkedHashMap<>();

    /** Builds the Message the HTTP listener would hand to the policy chain (HTTPMessage + HTTPPlugin attributes). */
    Message toMessage(File baseDir, String uriprefix) throws Exception {
        Message m = new Message(new CorrelationID(), null);
        URI uri = new URI(path);
        HeaderSet hs = new HeaderSet();
        boolean hasHost = false;
        for (Map.Entry<String, Object> h : headers.entrySet()) {
            hasHost |= h.getKey().equalsIgnoreCase("Host");
            if (h.getValue() instanceof Iterable) {
                for (Object v : (Iterable<?>) h.getValue()) {
                    hs.addHeader(h.getKey(), String.valueOf(v));
                }
            } else {
                hs.addHeader(h.getKey(), String.valueOf(h.getValue()));
            }
        }
        if (!hasHost) {
            hs.addHeader("Host", host);
        }

        byte[] payload = null;
        if (bodyFile != null) {
            File f = new File(bodyFile);
            payload = Files.readAllBytes((f.isAbsolute() || baseDir == null ? f : new File(baseDir, bodyFile)).toPath());
        } else if (body != null) {
            payload = body.getBytes(StandardCharsets.UTF_8);
        }
        String ct = contentType != null ? contentType : hs.getHeader("Content-Type");
        if (payload != null) {
            if (ct == null) {
                ct = "application/octet-stream";
            }
            if (hs.getHeader("Content-Type") == null) {
                hs.addHeader("Content-Type", ct);
            }
            if (hs.getHeader("Content-Length") == null) {
                hs.addHeader("Content-Length", String.valueOf(payload.length));
            }
        }
        HeaderSet bodyHeaders = new HeaderSet();
        if (ct != null) {
            bodyHeaders.addHeader("Content-Type", ct);
        }
        Body requestBody = Body.create(bodyHeaders,
                new ContentType(ContentType.Authority.MIME, ct == null ? "application/octet-stream" : ct),
                new ByteArrayContentSource(payload == null ? new byte[0] : payload));

        m.put("content.body", requestBody);
        m.put("http.headers", hs);
        m.put("http.request.uri", uri);
        m.put("http.request.path", uri.getPath());
        m.put("http.request.verb", method.toUpperCase());
        m.put("http.request.version", "HTTP/1.1");
        m.put("http.request.hostname", host);
        m.put("http.request.ip", clientIp);
        m.put("message.protocol.type", "http");
        m.put("message.source", "apd");
        m.put("http.request.incoming.path", uri.getPath());
        if (uriprefix != null) {
            m.put("resolved.to.path", uriprefix);
        }
        for (Map.Entry<String, Object> a : attributes.entrySet()) {
            m.put(a.getKey(), a.getValue());
        }
        return m;
    }
}
