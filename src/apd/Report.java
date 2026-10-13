package apd;

import java.io.PrintStream;
import java.util.List;
import java.util.Map;

/** Human-readable and JSON rendering of a lifecycle run. */
final class Report {

    static final boolean COLOR = System.getenv("NO_COLOR") == null && System.console() != null;

    static String c(String code, String s) {
        return COLOR ? "\u001b[" + code + "m" + s + "\u001b[0m" : s;
    }

    static void print(Lifecycle.Result r, boolean json, boolean quiet, PrintStream out) throws Exception {
        if (json) {
            out.println(Main.JSON.writeValueAsString(r));
            return;
        }
        if (!quiet) {
            tree(r.trace, out);
            out.println();
        }
        out.println(summary(r));
        if (!quiet) {
            if (r.failureReason != null && !r.outcome.equals("pass")) {
                out.println("  reason:  " + r.failureReason);
            }
            for (String b : r.blockedConnections) {
                out.println("  " + c("31", "sandbox blocked: ") + b + "  (--allow-network to permit)");
            }
            for (MockBackend.Call call : r.backendCalls) {
                out.println("  backend: " + call.method + " " + call.url + " -> " + call.status);
            }
            for (Map.Entry<String, Object> h : r.responseHeaders.entrySet()) {
                out.println("  " + h.getKey() + ": " + h.getValue());
            }
            if (r.responseBody != null && !r.responseBody.isEmpty()) {
                String body = r.responseBody.length() > 2000 ? r.responseBody.substring(0, 2000) + "..." : r.responseBody;
                out.println();
                out.println(body);
            }
        }
    }

    static String summary(Lifecycle.Result r) {
        String color = r.outcome.equals("pass") ? "32" : r.outcome.equals("fail") ? "33" : "31";
        return String.format("%s  %s %s  status=%s  %.1f ms", c(color, r.outcome.toUpperCase()), r.mode, r.target,
                r.status, r.millis);
    }

    static void tree(List<ExecutionTrace.Event> events, PrintStream out) {
        for (int i = 0; i < events.size(); i++) {
            ExecutionTrace.Event e = events.get(i);
            // a filter that called nested policies: its diff is the sum of the children's, shown below it
            boolean hasChildren = i + 1 < events.size() && events.get(i + 1).depth > e.depth;
            String indent = "  ".repeat(e.depth);
            switch (e.kind) {
                case "policy":
                    out.printf("%s%s %s  %s%s%n", indent, c("1", "▸"), c("1", e.policy), outcome(e.result),
                            e.millis > 0 ? String.format("  %.1f ms", e.millis) : "");
                    break;
                case "filter":
                    out.printf("%s• %-40s %-28s %s  %.2f ms%n", indent, e.filter, c("2", "[" + e.filterType + "]"),
                            outcome(e.result), e.millis);
                    if (e.changes != null && !hasChildren) {
                        for (ExecutionTrace.Change ch : e.changes) {
                            String arrow = ch.before == null ? c("32", "+ ") : ch.after == null ? c("31", "- ") : c("33", "~ ");
                            String val = ch.after == null ? ch.before : ch.after;
                            out.printf("%s    %s%s = %s%n", indent, arrow, ch.attribute, oneLine(val, 160));
                        }
                    }
                    break;
                case "abort":
                    out.printf("%s%s %s%n", indent, c("31", "✖ abort at " + e.filter + ":"), oneLine(e.detail, 200));
                    break;
                case "faulthandler":
                    out.printf("%s%s %s%n", indent, c("35", "↳ fault handler"), e.filter);
                    break;
                default:
                    break;
            }
        }
    }

    static String outcome(String r) {
        if (r == null) {
            return c("2", "…");
        }
        switch (r) {
            case "true":
            case "pass":
                return c("32", r);
            case "false":
            case "fail":
                return c("33", r);
            default:
                return c("31", r);
        }
    }

    static String oneLine(String s, int max) {
        if (s == null) {
            return "null";
        }
        String t = s.replace('\n', ' ').replace('\r', ' ');
        return t.length() > max ? t.substring(0, max) + "..." : t;
    }
}
