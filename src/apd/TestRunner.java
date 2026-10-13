package apd;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Runs YAML test cases against the in-process lifecycle. */
final class TestRunner {

    public static final class Expect {
        public String outcome;
        public Integer status;
        public Map<String, String> headers = new LinkedHashMap<>();
        public String bodyContains;
        public String bodyMatches;
        public String body;
        public Map<String, Object> attributes = new LinkedHashMap<>();
        public List<String> filters = new ArrayList<>();
        public List<String> notFilters = new ArrayList<>();
        public List<String> policies = new ArrayList<>();
        public Boolean faultHandler;
        public Double maxMillis;
        public List<BackendExpect> backend = new ArrayList<>();
    }

    /** Assertion on requests the gateway sent to mock backends. */
    public static final class BackendExpect {
        public String method;
        public String url;              // glob over host + path?query
        public Integer count;           // default: at least once
        public String bodyContains;
        public Map<String, String> headers = new LinkedHashMap<>();
    }

    public static final class Case {
        public String name;
        public String policy;
        public String path;
        public String service;
        public Map<String, String> chain;
        public RequestSpec request = new RequestSpec();
        public List<String> force = new ArrayList<>();
        public List<MockBackend.Route> mocks = new ArrayList<>();
        public Expect expect = new Expect();
        transient File file;
    }

    static final class Outcome {
        Case c;
        List<String> failures = new ArrayList<>();
        String error;
        double millis;
        Lifecycle.Result result;
    }

    private final GatewayRuntime rt;
    private final PolicyIndex index;
    private final boolean noDiff;
    private final boolean realRouting;

    TestRunner(GatewayRuntime rt, PolicyIndex index, boolean noDiff, boolean realRouting) {
        this.rt = rt;
        this.index = index;
        this.noDiff = noDiff;
        this.realRouting = realRouting;
    }

    int run(List<String> targets, String junit, boolean json, boolean verbose, PrintStream out) throws Exception {
        List<Case> cases = new ArrayList<>();
        for (String t : targets) {
            load(new File(t), cases);
        }
        List<Outcome> results = new ArrayList<>();
        int failed = 0;
        for (Case c : cases) {
            Outcome o = execute(c);
            results.add(o);
            boolean ok = o.error == null && o.failures.isEmpty();
            if (!ok) {
                failed++;
            }
            if (!json) {
                out.printf("%s  %s  %s(%.1f ms)%n", ok ? Report.c("32", "PASS") : Report.c("31", "FAIL"), c.name,
                        Report.c("2", c.file.getName() + " "), o.millis);
                if (o.error != null) {
                    out.println("      error: " + o.error);
                }
                for (String f : o.failures) {
                    out.println("      " + f);
                }
                if ((verbose || !ok) && o.result != null) {
                    Report.tree(o.result.trace, new IndentedStream(out));
                }
            }
        }
        if (json) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Outcome o : results) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", o.c.name);
                row.put("file", o.c.file.getPath());
                row.put("passed", o.error == null && o.failures.isEmpty());
                row.put("millis", o.millis);
                row.put("failures", o.failures);
                row.put("error", o.error);
                row.put("result", o.result);
                rows.add(row);
            }
            out.println(Main.JSON.writeValueAsString(rows));
        } else {
            out.printf("%n%d cases, %d passed, %d failed%n", cases.size(), cases.size() - failed, failed);
        }
        if (junit != null) {
            writeJunit(new File(junit), results);
        }
        return failed == 0 ? 0 : 4;
    }

    private void load(File f, List<Case> cases) throws Exception {
        if (f.isDirectory()) {
            File[] files = f.listFiles((d, n) -> n.endsWith(".yaml") || n.endsWith(".yml"));
            if (files != null) {
                Arrays.sort(files);
                for (File c : files) {
                    load(c, cases);
                }
            }
            return;
        }
        JsonNode node = Main.YAML.readTree(f);
        List<Case> found = node.isArray()
                ? Main.YAML.convertValue(node, new TypeReference<List<Case>>() { })
                : List.of(Main.YAML.convertValue(node, Case.class));
        int i = 1;
        for (Case c : found) {
            c.file = f;
            if (c.name == null) {
                c.name = f.getName() + (found.size() > 1 ? "#" + i : "");
            }
            i++;
            cases.add(c);
        }
    }

    private Outcome execute(Case c) {
        Outcome o = new Outcome();
        o.c = c;
        long t0 = System.nanoTime();
        try {
            ExecutionTrace trace = new ExecutionTrace(!noDiff);
            new Debugger(List.of(), false, c.force, false).install(trace);
            Lifecycle.Plan plan = new Lifecycle.Plan();
            plan.policy = c.policy;
            plan.path = c.path;
            plan.service = c.service;
            plan.chain = c.chain;
            Lifecycle.Result r = new Lifecycle(rt, index).run(plan, c.request, c.file.getParentFile(), trace, c.mocks, realRouting);
            o.result = r;
            check(c.expect, r, o.failures);
        } catch (Throwable t) {
            o.error = GatewayRuntime.describe(t);
        }
        o.millis = (System.nanoTime() - t0) / 1_000_000.0;
        return o;
    }

    private static void check(Expect x, Lifecycle.Result r, List<String> fail) {
        if (x.outcome != null && !x.outcome.equalsIgnoreCase(r.outcome)) {
            fail.add("outcome: expected " + x.outcome + ", got " + r.outcome
                    + (r.failureReason == null ? "" : " (" + r.failureReason + ")"));
        }
        if (x.status != null && !x.status.equals(r.status)) {
            fail.add("status: expected " + x.status + ", got " + r.status);
        }
        for (Map.Entry<String, String> h : x.headers.entrySet()) {
            Object actual = null;
            for (Map.Entry<String, Object> rh : r.responseHeaders.entrySet()) {
                if (rh.getKey().equalsIgnoreCase(h.getKey())) {
                    actual = rh.getValue();
                }
            }
            String a = actual instanceof List && ((List<?>) actual).size() == 1 ? String.valueOf(((List<?>) actual).get(0)) : String.valueOf(actual);
            if (actual == null || !(h.getValue().equals(a) || Pattern.compile(h.getValue()).matcher(a).matches())) {
                fail.add("header " + h.getKey() + ": expected " + h.getValue() + ", got " + actual);
            }
        }
        String body = r.responseBody == null ? "" : r.responseBody;
        if (x.body != null && !x.body.equals(body)) {
            fail.add("body: expected exactly " + Report.oneLine(x.body, 120) + ", got " + Report.oneLine(body, 120));
        }
        if (x.bodyContains != null && !body.contains(x.bodyContains)) {
            fail.add("body: expected to contain " + x.bodyContains + ", got " + Report.oneLine(body, 120));
        }
        if (x.bodyMatches != null && !Pattern.compile(x.bodyMatches, Pattern.DOTALL).matcher(body).find()) {
            fail.add("body: expected to match /" + x.bodyMatches + "/, got " + Report.oneLine(body, 120));
        }
        for (Map.Entry<String, Object> a : x.attributes.entrySet()) {
            Object v = r.message.get(a.getKey());
            String actual = v == null ? null : ExecutionTrace.render(v, 10_000);
            String expected = a.getValue() == null ? null : String.valueOf(a.getValue());
            if (expected == null ? actual != null : !expected.equals(actual)) {
                fail.add("attribute " + a.getKey() + ": expected " + expected + ", got " + actual);
            }
        }
        List<String> ran = new ArrayList<>();
        boolean faultRan = false;
        for (ExecutionTrace.Event e : r.trace) {
            if (e.kind.equals("filter")) {
                ran.add(e.filter);
            } else if (e.kind.equals("faulthandler")) {
                faultRan = true;
            }
        }
        int pos = 0;
        for (String want : x.filters) {
            Pattern p = Debugger.glob(want);
            while (pos < ran.size() && !p.matcher(ran.get(pos)).matches()) {
                pos++;
            }
            if (pos == ran.size()) {
                fail.add("filters: '" + want + "' not executed in the expected order; ran " + ran);
                break;
            }
            pos++;
        }
        for (String not : x.notFilters) {
            Pattern p = Debugger.glob(not);
            for (String f : ran) {
                if (p.matcher(f).matches()) {
                    fail.add("filters: '" + f + "' should not have executed");
                    break;
                }
            }
        }
        if (!x.policies.isEmpty() && !x.policies.equals(r.policiesRun)) {
            fail.add("policies: expected " + x.policies + ", got " + r.policiesRun);
        }
        if (x.faultHandler != null && x.faultHandler != faultRan) {
            fail.add("faultHandler: expected " + (x.faultHandler ? "invoked" : "not invoked"));
        }
        if (x.maxMillis != null && r.millis > x.maxMillis) {
            fail.add(String.format("time: %.1f ms exceeds maxMillis %.1f", r.millis, x.maxMillis));
        }
        for (BackendExpect b : x.backend) {
            Pattern url = b.url == null ? null : Debugger.glob(b.url);
            int n = 0;
            for (MockBackend.Call call : r.backendCalls) {
                if (b.method != null && !b.method.equalsIgnoreCase(call.method)) {
                    continue;
                }
                if (url != null && !url.matcher(call.url).matches()) {
                    continue;
                }
                if (b.bodyContains != null && (call.body == null || !call.body.contains(b.bodyContains))) {
                    continue;
                }
                boolean headersOk = true;
                for (Map.Entry<String, String> h : b.headers.entrySet()) {
                    List<String> values = null;
                    for (Map.Entry<String, List<String>> ch : call.headers.entrySet()) {
                        if (ch.getKey().equalsIgnoreCase(h.getKey())) {
                            values = ch.getValue();
                        }
                    }
                    if (values == null || !values.contains(h.getValue())) {
                        headersOk = false;
                    }
                }
                if (headersOk) {
                    n++;
                }
            }
            boolean ok = b.count == null ? n > 0 : n == b.count;
            if (!ok) {
                List<String> seen = new ArrayList<>();
                for (MockBackend.Call call : r.backendCalls) {
                    seen.add(call.method + " " + call.url);
                }
                fail.add("backend: expected " + (b.count == null ? "at least one" : String.valueOf(b.count)) + " call(s) matching "
                        + (b.method == null ? "" : b.method + " ") + b.url + ", got " + n + "; calls: " + seen);
            }
        }
    }

    private static void writeJunit(File f, List<Outcome> results) throws Exception {
        int failures = 0;
        int errors = 0;
        double total = 0;
        StringBuilder cases = new StringBuilder();
        for (Outcome o : results) {
            total += o.millis;
            cases.append(String.format("  <testcase classname=\"%s\" name=\"%s\" time=\"%.3f\">%n",
                    xml(o.c.file.getName()), xml(o.c.name), o.millis / 1000));
            if (o.error != null) {
                errors++;
                cases.append("    <error message=\"").append(xml(o.error)).append("\"/>\n");
            } else if (!o.failures.isEmpty()) {
                failures++;
                cases.append("    <failure message=\"").append(xml(o.failures.get(0))).append("\">")
                        .append(xml(String.join("\n", o.failures))).append("</failure>\n");
            }
            cases.append("  </testcase>\n");
        }
        String doc = String.format("<?xml version=\"1.0\" encoding=\"UTF-8\"?>%n<testsuite name=\"apimgr-policy-debugger\" tests=\"%d\" failures=\"%d\" errors=\"%d\" time=\"%.3f\">%n%s</testsuite>%n",
                results.size(), failures, errors, total / 1000, cases);
        Files.write(f.toPath(), doc.getBytes(StandardCharsets.UTF_8));
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** Indents nested trace output under a test line. */
    private static final class IndentedStream extends PrintStream {
        IndentedStream(PrintStream target) {
            super(new java.io.OutputStream() {
                boolean lineStart = true;

                @Override
                public void write(int b) {
                    if (lineStart) {
                        target.print("      ");
                        lineStart = false;
                    }
                    target.write(b);
                    if (b == '\n') {
                        lineStart = true;
                    }
                }
            }, true);
        }
    }
}
