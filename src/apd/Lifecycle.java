package apd;

import com.vordel.circuit.CircuitChainFilter;
import com.vordel.circuit.CircuitReference;
import com.vordel.circuit.FilterContainerImpl;
import com.vordel.circuit.InvocationContext;
import com.vordel.circuit.InvocationEngine;
import com.vordel.circuit.Message;
import com.vordel.circuit.MessageProcessor;
import com.vordel.circuit.SoftCircuitReference;
import com.vordel.circuit.SyntheticCircuitChainProcessor;
import com.vordel.common.apiserver.service.CoreServiceRegistry;
import com.vordel.config.Circuit;
import com.vordel.dispose.Disposer;
import com.vordel.es.ESPK;
import com.vordel.es.Entity;
import com.vordel.es.EntityStore;
import com.vordel.es.EntityStoreDelegate;
import com.vordel.es.xes.PortableESPK;
import com.vordel.mime.Body;
import com.vordel.mime.HeaderSet;
import com.vordel.runtime.interfaces.CircuitService;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Drives a request through the same policy chain the HTTP listener builds
 * (HTTPPlugin.configureCircuits + SyntheticCircuitChainProcessor), or through a
 * single policy / an explicit chain, using the engine's InvocationEngine.
 */
final class Lifecycle {

    /** What to execute. Exactly one of policy, path or chain is used. */
    static final class Plan {
        String policy;                 // single policy
        String path;                   // listener relative path (auto when null and policy/chain null)
        String service;                // optional HTTP service name to disambiguate paths
        Map<String, String> chain;     // globalRequest, request, routing, response, globalResponse
    }

    static final class Result {
        public String mode;
        public String target;
        public String outcome;                     // pass | fail | abort | error
        public Integer status;
        public String statusText;
        public Map<String, Object> responseHeaders = new LinkedHashMap<>();
        public String responseBody;
        public String failureReason;
        public double millis;
        public List<ExecutionTrace.Event> trace;
        public List<String> policiesRun = new LinkedList<>();
        public List<MockBackend.Call> backendCalls = new LinkedList<>();
        public List<String> blockedConnections = new LinkedList<>();
        transient Message message;
    }

    private final GatewayRuntime rt;
    private final PolicyIndex index;

    Lifecycle(GatewayRuntime rt, PolicyIndex index) {
        this.rt = rt;
        this.index = index;
    }

    /**
     * Sandbox (default): routing filters are answered by the virtual backend, nothing leaves the process.
     * realRouting: the native HTTP client really connects, to local mock servers (requires a gateway
     * license with unrestricted outbound connections).
     */
    Result run(Plan plan, RequestSpec req, File baseDir, ExecutionTrace trace, List<MockBackend.Route> mocks,
               boolean realRouting) throws Exception {
        if (realRouting) {
            if (mocks == null || mocks.isEmpty()) {
                return run(plan, req, baseDir, trace);
            }
            try (MockBackend backend = new MockBackend(mocks, baseDir)) {
                Result r = run(plan, req, baseDir, trace);
                r.backendCalls.addAll(backend.calls);
                return r;
            }
        }
        VirtualBackend vb = new VirtualBackend(mocks, baseDir);
        DebugProcessor.virtualBackend = vb;
        try {
            Result r = run(plan, req, baseDir, trace);
            r.backendCalls.addAll(vb.calls);
            return r;
        } finally {
            DebugProcessor.virtualBackend = null;
        }
    }

    Result run(Plan plan, RequestSpec req, File baseDir, ExecutionTrace trace) throws Exception {
        Result r = new Result();
        NetworkGuard.blocked.clear();
        MessageProcessor lastChance =((CircuitService) CoreServiceRegistry.getInstance()
                .getService(CircuitService.class)).getLastChanceMessageProcessor();
        Disposer.pushForThread();
        try {
            Message m;
            int stat;
            long t0 = System.nanoTime();
            if (plan.policy != null) {
                PolicyIndex.Policy p = index.resolve(plan.policy);
                r.mode = "policy";
                r.target = p.path;
                m = req.toMessage(baseDir, null);
                m.addMessageListener(trace);
                Circuit c = requireCircuit(p.pk, p.path);
                stat = InvocationEngine.processMessage(new InvocationContext(null, lastChance, c), m);
            } else {
                List<CircuitReference> refs;
                String prefix = null;
                if (plan.chain != null) {
                    r.mode = "chain";
                    r.target = String.valueOf(plan.chain);
                    refs = explicitChain(plan.chain);
                } else {
                    String requestPath = plan.path != null ? plan.path : req.path.split("\\?")[0];
                    PolicyIndex.RelativePath rp = index.matchPath(requestPath, req.method, plan.service);
                    if (rp == null) {
                        throw new IllegalArgumentException("No listener path matches " + req.method + " " + requestPath
                                + " (use `paths` to list them, or run a policy with --policy)");
                    }
                    r.mode = "path";
                    r.target = rp.service + " " + rp.uriprefix;
                    prefix = rp.uriprefix;
                    refs = pathChain(rp.entity);
                }
                m = req.toMessage(baseDir, prefix);
                m.addMessageListener(trace);
                CircuitChainFilter ccf = new CircuitChainFilter();
                ccf.setName("Integral Policy Shortcut Chain");
                ccf.setRefs(refs);
                SyntheticCircuitChainProcessor ccp = (SyntheticCircuitChainProcessor)
                        FilterContainerImpl.createFilterContainer(SyntheticCircuitChainProcessor.class, ccf, null);
                ccp.attach(rt.pack, ccf);
                stat = ccp.invoke(m, lastChance, "apd");
            }
            r.millis = (System.nanoTime() - t0) / 1_000_000.0;
            r.outcome = stat == 2 ? "pass" : stat == 3 ? "fail" : "abort";
            r.message = m;
            collectResponse(r, m);
            for (ExecutionTrace.Event e : trace.events) {
                if (e.kind.equals("policy") && e.depth == 0) {
                    r.policiesRun.add(e.policy);
                }
            }
            r.trace = trace.events;
            r.blockedConnections.addAll(NetworkGuard.blocked);
            return r;
        } finally {
            Disposer.popForThread().disposeAll();
        }
    }

    private Circuit requireCircuit(ESPK pk, String name) {
        Circuit c = rt.circuit(pk);
        if (c == null) {
            throw new IllegalStateException("Policy '" + name + "' failed to configure (see trace output above)");
        }
        return c;
    }

    /** Same ordering logic as HTTPPlugin.configureCircuits. */
    private List<CircuitReference> pathChain(Entity path) throws Exception {
        EntityStore es = rt.store;
        LinkedList<CircuitReference> refs = new LinkedList<>();
        for (Entity ref : EntityStoreDelegate.getSortedChildEntities(es, path.getPK(), es.getTypeForName("CircuitReference"), new String[]{"priority"})) {
            refs.add(CircuitChainFilter.DEFAULT_CR_FACTORY.createCircuitReference(ref));
        }
        CircuitReference main = new CircuitReference(path.getReferenceValue("filterCircuit"));
        main.label = "PATH_SPECIFIC_POLICY";
        main.setEnabled(path.getBooleanValue("evaluate"));
        refs.add(refs.size() == 4 ? 2 : 0, main);
        return refs;
    }

    /** Lets a test pick each lifecycle slot; "global" keeps the configured global request/response policy. */
    private List<CircuitReference> explicitChain(Map<String, String> chain) {
        LinkedList<CircuitReference> refs = new LinkedList<>();
        addSlot(refs, chain.get("globalRequest"), "GLOBAL_POLICY_REQ", "system.policy.request");
        addSlot(refs, chain.get("request"), "CUSTOM_POLICY_REQ", null);
        addSlot(refs, chain.get("routing"), "PATH_SPECIFIC_POLICY", null);
        addSlot(refs, chain.get("response"), "CUSTOM_POLICY_RSP", null);
        addSlot(refs, chain.get("globalResponse"), "GLOBAL_POLICY_RSP", "system.policy.response");
        return refs;
    }

    private void addSlot(List<CircuitReference> refs, String value, String label, String globalProperty) {
        CircuitReference ref;
        if (value == null || value.isEmpty() || value.equalsIgnoreCase("none")) {
            return;
        }
        if (globalProperty != null && value.equalsIgnoreCase("global")) {
            ref = new SoftCircuitReference(globalProperty);
        } else {
            ref = new CircuitReference(PortableESPK.toPortableKey(rt.store, index.resolve(value).pk));
        }
        ref.label = label;
        ref.setEnabled(true);
        refs.add(ref);
    }

    /** Same response assembly as HTTPPlugin.sendResponse. */
    private static void collectResponse(Result r, Message m) {
        Object status = m.get("http.response.status");
        r.status = status instanceof Integer ? (Integer) status : status == null ? 500 : Integer.valueOf(String.valueOf(status));
        Object info = m.get("http.response.info");
        r.statusText = info == null ? null : String.valueOf(info);
        Object reason = m.get("circuit.failure.reason");
        r.failureReason = reason == null ? null : String.valueOf(reason);

        HeaderSet headers = new HeaderSet();
        Object reqHeaders = m.get("http.headers");
        if (reqHeaders instanceof HeaderSet) {
            headers = (HeaderSet) ((HeaderSet) reqHeaders).clone();
            for (String h : new String[]{"X-CorrelationID", "Origin", "Access-Control-Request-Method",
                    "Access-Control-Request-Headers", "Strict-Transport-Security"}) {
                headers.remove(h);
            }
        }
        Object rspHeaders = m.get("http.response.headers");
        if (rspHeaders instanceof HeaderSet) {
            HeaderSet rh = (HeaderSet) rspHeaders;
            for (java.util.Iterator<String> it = rh.getHeaderNames(); it.hasNext(); ) {
                String n = it.next();
                headers.setHeaderEntry(n, rh.getHeaderEntry(n));
            }
        }
        Object body = m.get("content.body");
        if (body instanceof Body) {
            // the transport writes the body's own headers (Content-Type...) along with http.headers
            HeaderSet bodyHeaders = ((Body) body).getHeaders();
            if (bodyHeaders != null) {
                for (java.util.Iterator<String> it = bodyHeaders.getHeaderNames(); it.hasNext(); ) {
                    String n = it.next();
                    if (headers.getHeader(n) == null) {
                        headers.setHeaderEntry(n, bodyHeaders.getHeaderEntry(n));
                    }
                }
            }
            r.responseBody = readBody((Body) body, 1 << 20);
        }
        r.responseHeaders.putAll(ExecutionTrace.headers(headers));
    }

    static String readBody(Body b, int max) {
        try (InputStream in = b.getInputStream(0)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0 && out.size() < max; ) {
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "<unreadable body: " + GatewayRuntime.describe(e) + ">";
        }
    }
}
