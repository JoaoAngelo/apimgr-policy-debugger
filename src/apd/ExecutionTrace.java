package apd;

import com.vordel.circuit.CircuitAbortException;
import com.vordel.circuit.Filter;
import com.vordel.circuit.Message;
import com.vordel.circuit.MessageListener;
import com.vordel.circuit.MessageProcessor;
import com.vordel.config.Circuit;
import com.vordel.es.Entity;
import com.vordel.mime.Body;
import com.vordel.mime.HeaderSet;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Records the engine's own lifecycle callbacks (InvocationEngine fires them for
 * every policy, filter, abort and fault handler) into an ordered event list.
 */
public final class ExecutionTrace implements MessageListener {

    /** Hooks the interactive debugger plugs into. */
    public interface Hooks {
        void beforeFilter(ExecutionTrace trace, Event e, Message m);

        void afterFilter(ExecutionTrace trace, Event e, Message m);

        void onAbort(ExecutionTrace trace, Event e, Message m, CircuitAbortException cae);
    }

    public static final class Change {
        public String attribute;
        public String before;
        public String after;

        Change(String attribute, String before, String after) {
            this.attribute = attribute;
            this.before = before;
            this.after = after;
        }
    }

    public static final class Event {
        public String kind;          // policy | filter | abort | faulthandler
        public int depth;
        public String policy;
        public String filter;
        public String filterType;
        public String result;        // true | false | abort | pass | fail
        public double millis;
        public String detail;
        public List<Change> changes;
        transient long started;
        transient Map<String, String> snapshot;
    }

    public final List<Event> events = new ArrayList<>();
    private final Deque<Event> stack = new ArrayDeque<>();
    private final boolean diff;
    volatile Hooks hooks;

    public ExecutionTrace(boolean diff) {
        this.diff = diff;
    }

    public void preCircuitProcessing(Circuit c, Message m, Object ctx) {
    }

    public void postCircuitProcessing(Message m, int result) {
    }

    public void preCircuitInvocation(Circuit c, Message m, Object ctx) {
        Event e = new Event();
        e.kind = "policy";
        e.depth = stack.size();
        e.policy = c.getName();
        e.detail = ctx == null ? null : String.valueOf(ctx);
        e.started = System.nanoTime();
        events.add(e);
        stack.push(e);
    }

    public void postCircuitInvocation(Circuit c, Message m, boolean result, Object ctx) {
        Event e = popUntil("policy");
        if (e != null) {
            e.result = result ? "pass" : "fail";
            e.millis = elapsed(e);
        }
    }

    public void abortedCircuitInvocation(Circuit c, Message m, CircuitAbortException cae) {
        Event e = new Event();
        e.kind = "abort";
        e.depth = stack.size();
        e.policy = c.getName();
        e.filter = cae.node == null ? null : filterName(cae.node);
        e.result = "abort";
        e.detail = cae.getMessage();
        events.add(e);
        Hooks h = hooks;
        if (h != null) {
            h.onAbort(this, e, m, cae);
        }
    }

    public void preFaultHandlerInvocation(Circuit c, MessageProcessor handler, Message m, CircuitAbortException cae) {
        Event e = new Event();
        e.kind = "faulthandler";
        e.depth = stack.size();
        e.policy = c.getName();
        e.filter = filterName(handler);
        e.detail = cae == null ? null : cae.getMessage();
        events.add(e);
    }

    public void preFilterInvocation(Circuit c, MessageProcessor node, Message m, MessageProcessor caller, Object ctx) {
        Event e = new Event();
        e.kind = "filter";
        e.depth = stack.size();
        e.policy = c.getName();
        e.filter = filterName(node);
        e.filterType = filterType(node);
        events.add(e);
        stack.push(e);
        Hooks h = hooks;
        if (h != null) {
            h.beforeFilter(this, e, m);
        }
        // after the debugger prompt, so edits made while paused are not attributed to the filter
        if (diff) {
            e.snapshot = snapshot(m);
        }
        e.started = System.nanoTime();
    }

    public void postFilterInvocation(Circuit c, MessageProcessor node, Message m, int resultType, MessageProcessor caller, Object ctx) {
        Event e = popUntil("filter");
        if (e == null) {
            return;
        }
        e.millis = elapsed(e);
        e.result = resultType == 1 ? "true" : resultType == 0 ? "false" : "abort";
        if (e.snapshot != null) {
            e.changes = changes(e.snapshot, snapshot(m));
            e.snapshot = null;
        }
        Hooks h = hooks;
        if (h != null) {
            h.afterFilter(this, e, m);
        }
    }

    public void onMessageCompletion(Message m) {
    }

    public Event current() {
        return stack.peek();
    }

    public List<Event> callStack() {
        return new ArrayList<>(stack);
    }

    private Event popUntil(String kind) {
        while (!stack.isEmpty()) {
            Event e = stack.pop();
            if (e.kind.equals(kind)) {
                return e;
            }
        }
        return null;
    }

    private static double elapsed(Event e) {
        return e.started == 0 ? 0 : (System.nanoTime() - e.started) / 1_000_000.0;
    }

    static String filterName(MessageProcessor node) {
        Filter f = node.getFilter();
        return f == null ? node.getClass().getSimpleName() : f.getName();
    }

    static String filterType(MessageProcessor node) {
        Filter f = node.getFilter();
        if (f == null) {
            return node.getClass().getSimpleName();
        }
        Entity entity = f.getEntity();
        return entity == null ? f.getClass().getSimpleName() : entity.getType().getName();
    }

    static Map<String, String> snapshot(Message m) {
        Map<String, String> s = new TreeMap<>();
        for (Map.Entry<String, Object> en : m.entrySet()) {
            s.put(en.getKey(), render(en.getValue(), 300));
        }
        return s;
    }

    private static List<Change> changes(Map<String, String> before, Map<String, String> after) {
        List<Change> out = new ArrayList<>();
        for (Map.Entry<String, String> a : after.entrySet()) {
            String b = before.get(a.getKey());
            if (b == null) {
                out.add(new Change(a.getKey(), null, a.getValue()));
            } else if (!b.equals(a.getValue())) {
                out.add(new Change(a.getKey(), b, a.getValue()));
            }
        }
        for (String k : before.keySet()) {
            if (!after.containsKey(k)) {
                out.add(new Change(k, before.get(k), null));
            }
        }
        return out;
    }

    /** Short, stable rendering used for diffs and the debugger; bodies are not consumed. */
    static String render(Object v, int max) {
        String s;
        if (v == null) {
            s = "null";
        } else if (v instanceof Body) {
            Body b = (Body) v;
            s = "Body[" + b.getContentType() + "]@" + Integer.toHexString(System.identityHashCode(b));
        } else if (v instanceof HeaderSet) {
            s = headers((HeaderSet) v).toString();
        } else if (v instanceof CircuitAbortException) {
            s = "CircuitAbortException: " + ((CircuitAbortException) v).getMessage();
        } else {
            try {
                s = String.valueOf(v);
            } catch (Throwable t) {
                s = v.getClass().getName();
            }
        }
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    static Map<String, List<String>> headers(HeaderSet hs) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Iterator<String> names = hs.getHeaderNames(); names.hasNext(); ) {
            String n = names.next();
            List<String> values = new ArrayList<>();
            for (Iterator<String> it = hs.getHeaders(n); it.hasNext(); ) {
                values.add(it.next());
            }
            out.put(n, values);
        }
        return out;
    }
}
