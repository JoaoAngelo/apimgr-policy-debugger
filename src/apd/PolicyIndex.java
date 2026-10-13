package apd;

import com.vordel.es.ESPK;
import com.vordel.es.Entity;
import com.vordel.es.EntityStore;
import com.vordel.es.EntityType;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Names policies and relative paths the way Policy Studio shows them. */
final class PolicyIndex {

    static final class Policy {
        final ESPK pk;
        final String name;
        final String path;

        Policy(ESPK pk, String name, String path) {
            this.pk = pk;
            this.name = name;
            this.path = path;
        }
    }

    /** A listener path (HTTPPlugin entity): uriprefix + main policy + ordered policy references. */
    static final class RelativePath {
        final Entity entity;
        final String uriprefix;
        final String service;
        final String httpMethod;

        RelativePath(Entity entity, String uriprefix, String service, String httpMethod) {
            this.entity = entity;
            this.uriprefix = uriprefix;
            this.service = service;
            this.httpMethod = httpMethod;
        }
    }

    final Map<String, Policy> byPath = new LinkedHashMap<>();
    final List<RelativePath> paths = new ArrayList<>();
    private final EntityStore store;
    private final EntityType anyType;

    PolicyIndex(GatewayRuntime rt) throws Exception {
        this.store = rt.store;
        this.anyType = store.getTypeForName("Entity");
        for (Entity c : rt.entities("/[CircuitContainer]**/[FilterCircuit]")) {
            add(c);
        }
        for (Entity c : rt.entities("/[FilterCircuit]")) {
            add(c);
        }
        Entity service = rt.safeEntity("/[NetService]name=Service");
        if (service != null) {
            collectPaths(service);
        }
    }

    private void add(Entity circuit) throws Exception {
        String name = circuit.getStringValue("name");
        Deque<String> parts = new ArrayDeque<>();
        parts.push(name);
        ESPK parent = circuit.getParentPK();
        while (parent != null && !parent.equals(store.getRootPK())) {
            Entity p = store.getEntity(parent);
            if (p == null) {
                break;
            }
            if (p.getType().getName().equals("CircuitContainer")) {
                parts.push(p.getStringValue("name"));
            }
            parent = p.getParentPK();
        }
        String path = String.join("/", parts);
        byPath.put(path, new Policy(circuit.getPK(), name, path));
    }

    private void collectPaths(Entity root) throws Exception {
        Deque<Entity> todo = new ArrayDeque<>();
        Deque<String> svc = new ArrayDeque<>();
        todo.push(root);
        svc.push("");
        while (!todo.isEmpty()) {
            Entity e = todo.pop();
            String service = svc.pop();
            EntityType t = e.getType();
            if (t.getName().equals("HTTP") && e.containsKey("name")) {
                service = e.getStringValue("name");
            }
            Collection<String> fields = t.getAllFieldNames();
            if (fields.contains("uriprefix") && fields.contains("filterCircuit")) {
                String method = fields.contains("httpMethod") ? e.getStringValue("httpMethod") : "*";
                paths.add(new RelativePath(e, e.getStringValue("uriprefix"), service, method));
            }
            for (ESPK child : store.listChildren(e.getPK(), anyType)) {
                Entity ce = store.getEntity(child);
                if (ce != null) {
                    todo.push(ce);
                    svc.push(service);
                }
            }
        }
        paths.sort((a, b) -> a.uriprefix.compareTo(b.uriprefix));
    }

    /** Resolves "Container/Policy", a unique policy name, or a case-insensitive unique suffix. */
    Policy resolve(String ref) {
        Policy exact = byPath.get(ref);
        if (exact != null) {
            return exact;
        }
        List<Policy> matches = new ArrayList<>();
        String lower = ref.toLowerCase(Locale.ROOT);
        for (Policy p : byPath.values()) {
            if (p.name.equals(ref) || p.path.toLowerCase(Locale.ROOT).endsWith("/" + lower)) {
                matches.add(p);
            }
        }
        if (matches.size() == 1) {
            return matches.get(0);
        }
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("Policy not found: " + ref + " (use `policies` to list them)");
        }
        StringBuilder sb = new StringBuilder("Ambiguous policy '" + ref + "', candidates:");
        for (Policy p : matches) {
            sb.append("\n  ").append(p.path);
        }
        throw new IllegalArgumentException(sb.toString());
    }

    Policy byPk(ESPK pk) {
        if (pk == null) {
            return null;
        }
        for (Policy p : byPath.values()) {
            if (p.pk.equals(pk)) {
                return p;
            }
        }
        try {
            // cross-store references (ListenersStore -> PrimaryStore) resolve to the canonical key via the entity
            Entity e = store.getEntity(pk);
            if (e != null) {
                for (Policy p : byPath.values()) {
                    if (p.pk.equals(e.getPK())) {
                        return p;
                    }
                }
            }
        } catch (Exception ignored) {
            // dangling reference
        }
        return null;
    }

    /** Longest-prefix match, as the HTTP listener resolves paths. */
    RelativePath matchPath(String requestPath, String method, String service) {
        RelativePath best = null;
        for (RelativePath p : paths) {
            if (service != null && !service.equals(p.service)) {
                continue;
            }
            if (p.httpMethod != null && !p.httpMethod.equals("*") && !p.httpMethod.equalsIgnoreCase(method)) {
                continue;
            }
            if (requestPath.startsWith(p.uriprefix) && (best == null || p.uriprefix.length() > best.uriprefix.length())) {
                best = p;
            }
        }
        return best;
    }
}
