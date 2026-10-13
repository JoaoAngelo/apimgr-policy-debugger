package apd;

import com.vordel.circuit.CircuitAbortException;
import com.vordel.circuit.Message;
import com.vordel.config.Circuit;
import com.vordel.mime.Body;
import com.vordel.mime.HeaderSet;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Breakpoints, stepping, attribute inspection/editing and forced filter
 * outcomes, driven by the engine's own listener callbacks.
 */
final class Debugger implements ExecutionTrace.Hooks, DebugProcessor.OutcomeOracle {

    private enum StepMode { NONE, INTO, OVER }

    private final List<String> breakpoints = new ArrayList<>();
    private final Map<Pattern, DebugProcessor.Outcome> forced = new LinkedHashMap<>();
    private final Map<Pattern, String> forcedMessages = new LinkedHashMap<>();
    private final boolean interactive;
    private final PrintStream out = System.out;
    private BufferedReader in;
    private StepMode step;
    private int stepDepth;
    private DebugProcessor.Outcome pending;
    private String pendingMessage;
    private boolean faultNext;
    private ExecutionTrace trace;

    Debugger(List<String> breakpoints, boolean stepFirst, List<String> forces, boolean interactive) {
        this.breakpoints.addAll(breakpoints);
        this.step = stepFirst ? StepMode.INTO : StepMode.NONE;
        this.interactive = interactive;
        for (String f : forces) {
            addForce(f);
        }
    }

    void install(ExecutionTrace trace) {
        this.trace = trace;
        trace.hooks = this;
        DebugProcessor.oracle = this;
    }

    void addForce(String spec) {
        int i = spec.lastIndexOf('=');
        if (i < 1) {
            throw new IllegalArgumentException("--force expects '<filter>=true|false|abort[:message]', got: " + spec);
        }
        String value = spec.substring(i + 1).trim();
        String message = null;
        int colon = value.indexOf(':');
        if (colon > 0) {
            message = value.substring(colon + 1);
            value = value.substring(0, colon);
        }
        DebugProcessor.Outcome o = DebugProcessor.Outcome.valueOf(value.toUpperCase(Locale.ROOT));
        Pattern p = glob(spec.substring(0, i).trim());
        forced.put(p, o);
        forcedMessages.put(p, message);
    }

    static Pattern glob(String g) {
        StringBuilder sb = new StringBuilder();
        for (char ch : g.toCharArray()) {
            if (ch == '*') {
                sb.append(".*");
            } else {
                sb.append(Pattern.quote(String.valueOf(ch)));
            }
        }
        return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
    }

    // ---- OutcomeOracle (called from DebugProcessor.invoke, right after beforeFilter) ----

    public DebugProcessor.Outcome decide(Circuit circuit, DebugProcessor node, Message m) {
        if (pending != null) {
            DebugProcessor.Outcome o = pending;
            pending = null;
            return o;
        }
        String name = ExecutionTrace.filterName(node);
        for (Map.Entry<Pattern, DebugProcessor.Outcome> f : forced.entrySet()) {
            if (f.getKey().matcher(name).matches()) {
                pendingMessage = forcedMessages.get(f.getKey());
                return f.getValue();
            }
        }
        return DebugProcessor.Outcome.RUN;
    }

    public String abortMessage(DebugProcessor node) {
        String msg = pendingMessage;
        pendingMessage = null;
        return msg != null ? msg : "apd: forced abort at '" + ExecutionTrace.filterName(node) + "'";
    }

    // ---- Hooks ----

    public void beforeFilter(ExecutionTrace t, ExecutionTrace.Event e, Message m) {
        boolean isFault = faultNext;
        faultNext = false;
        if (!interactive) {
            return;
        }
        String reason = null;
        if (step == StepMode.INTO || (step == StepMode.OVER && e.depth <= stepDepth)) {
            reason = "step";
        } else {
            for (String bp : breakpoints) {
                if (matches(bp, e, isFault)) {
                    reason = "breakpoint " + bp;
                    break;
                }
            }
        }
        if (reason != null) {
            out.printf("%n%s %s  in policy %s  %s%n", Report.c("36", "■ " + reason + ":"), Report.c("1", e.filter),
                    e.policy, Report.c("2", "[" + e.filterType + "]"));
            prompt(e, m, true);
        }
    }

    public void afterFilter(ExecutionTrace t, ExecutionTrace.Event e, Message m) {
        if (interactive && step != StepMode.NONE) {
            out.printf("  → %s %s (%.2f ms)%n", e.filter, Report.outcome(e.result), e.millis);
            if (e.changes != null) {
                for (ExecutionTrace.Change ch : e.changes) {
                    out.printf("     %s = %s%n", ch.attribute, Report.oneLine(ch.after, 160));
                }
            }
        }
    }

    public void onAbort(ExecutionTrace t, ExecutionTrace.Event e, Message m, CircuitAbortException cae) {
        faultNext = true;
        if (interactive && (breakpoints.contains("abort") || step != StepMode.NONE)) {
            out.printf("%n%s policy %s, filter %s: %s%n", Report.c("31", "■ abort:"), e.policy, e.filter, e.detail);
            prompt(e, m, false);
        }
    }

    private boolean matches(String bp, ExecutionTrace.Event e, boolean isFault) {
        if (bp.equals("abort")) {
            return false;
        }
        if (bp.equals("fault")) {
            return isFault;
        }
        if (bp.startsWith("policy:")) {
            ExecutionTrace.Event parent = trace.events.size() >= 2 ? trace.events.get(trace.events.size() - 2) : null;
            return glob(bp.substring(7)).matcher(e.policy).matches() && parent != null && parent.kind.equals("policy");
        }
        return glob(bp).matcher(e.filter == null ? "" : e.filter).matches();
    }

    private void prompt(ExecutionTrace.Event e, Message m, boolean beforeRun) {
        if (in == null) {
            in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        }
        while (true) {
            out.print(Report.c("36", "(apd) "));
            out.flush();
            String line;
            try {
                line = in.readLine();
            } catch (Exception ex) {
                line = null;
            }
            if (line == null) {
                step = StepMode.NONE;
                breakpoints.clear();
                return;
            }
            line = line.trim();
            String[] w = line.split("\\s+", 3);
            switch (w[0]) {
                case "":
                case "s":
                case "step":
                    step = StepMode.INTO;
                    return;
                case "n":
                case "next":
                    step = StepMode.OVER;
                    stepDepth = e.depth;
                    return;
                case "c":
                case "continue":
                    step = StepMode.NONE;
                    return;
                case "p":
                case "print":
                    if (w.length < 2) {
                        out.println("usage: p <attribute>");
                    } else {
                        printValue(w[1], m.get(w[1]));
                    }
                    break;
                case "attrs":
                    for (Map.Entry<String, String> a : new TreeMap<>(ExecutionTrace.snapshot(m)).entrySet()) {
                        if (w.length < 2 || a.getKey().startsWith(w[1])) {
                            out.printf("  %-40s %s%n", a.getKey(), Report.oneLine(a.getValue(), 120));
                        }
                    }
                    break;
                case "body":
                    printValue("content.body", m.get("content.body"));
                    break;
                case "eval":
                    if (w.length < 2) {
                        out.println("usage: eval <selector>   e.g. eval ${http.request.uri}  eval ${env.PORT.TRAFFIC}");
                    } else {
                        try {
                            Object v = new com.vordel.el.Selector<>(line.substring(4).trim(), Object.class).substitute(m);
                            printValue("value", v);
                        } catch (Throwable ex) {
                            out.println("  error: " + GatewayRuntime.describe(ex));
                        }
                    }
                    break;
                case "set":
                    if (w.length < 3) {
                        out.println("usage: set <attribute> <value>   (value: text, or int:<n>, bool:<b>, null)");
                    } else {
                        m.put(w[1], parse(w[2]));
                        out.println("  " + w[1] + " = " + m.get(w[1]));
                    }
                    break;
                case "unset":
                    if (w.length >= 2) {
                        m.remove(w[1]);
                    }
                    break;
                case "force":
                    if (!beforeRun) {
                        out.println("force applies before a filter runs");
                    } else if (w.length < 2) {
                        out.println("usage: force true|false|abort [message]");
                    } else {
                        pending = DebugProcessor.Outcome.valueOf(w[1].toUpperCase(Locale.ROOT));
                        pendingMessage = w.length > 2 ? w[2] : null;
                        out.println("  next run of '" + e.filter + "' -> " + pending);
                    }
                    break;
                case "bt":
                    for (ExecutionTrace.Event f : trace.callStack()) {
                        out.printf("  %s %s%n", f.kind, f.kind.equals("policy") ? f.policy : f.filter);
                    }
                    break;
                case "trace":
                    Report.tree(trace.events, out);
                    break;
                case "b":
                    if (w.length >= 2) {
                        breakpoints.add(line.substring(2).trim());
                    }
                    out.println("  breakpoints: " + breakpoints);
                    break;
                case "d":
                    if (w.length >= 2) {
                        breakpoints.remove(line.substring(2).trim());
                    }
                    out.println("  breakpoints: " + breakpoints);
                    break;
                case "q":
                case "quit":
                    System.out.flush();
                    Runtime.getRuntime().halt(130);
                    return;
                case "h":
                case "help":
                default:
                    out.println("  s|step  n|next  c|continue  p <attr>  attrs [prefix]  body  eval <selector>  set <attr> <value>  unset <attr>");
                    out.println("  force true|false|abort [msg]  bt  trace  b <bp>  d <bp>  q|quit");
                    break;
            }
        }
    }

    private void printValue(String name, Object v) {
        if (v instanceof Body) {
            Body b = (Body) v;
            out.println("  " + name + " (" + b.getContentType() + "):");
            out.println(Lifecycle.readBody(b, 64 * 1024));
        } else if (v instanceof HeaderSet) {
            ExecutionTrace.headers((HeaderSet) v).forEach((k, vals) -> out.println("  " + k + ": " + vals));
        } else {
            out.println("  " + name + " = " + (v == null ? "null" : v + "  (" + v.getClass().getName() + ")"));
        }
    }

    static Object parse(String s) {
        if (s.equals("null")) {
            return null;
        }
        if (s.startsWith("int:")) {
            return Integer.valueOf(s.substring(4));
        }
        if (s.startsWith("long:")) {
            return Long.valueOf(s.substring(5));
        }
        if (s.startsWith("bool:")) {
            return Boolean.valueOf(s.substring(5));
        }
        return s;
    }
}
