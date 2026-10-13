package apd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.File;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** apimgr-policy-debugger command line. */
public final class Main {

    static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    static final String USAGE = String.join("\n",
            "usage: apimgr-policy-debugger <command> --config <.fed|.pol|dir|yaml-dir> [options]",
            "",
            "commands:",
            "  doctor                    check the runtime, modules and sandbox isolation",
            "  info                      runtime and module status",
            "  policies [text]           list policies (optionally filtered)",
            "  paths                     list listener relative paths and their policy chain",
            "  run                       execute a request through the lifecycle and print the trace",
            "  debug                     like run, with breakpoints and an interactive prompt",
            "  test <case.yaml|dir>...   run test cases (--junit <file> for CI)",
            "  shell                     boot once, then run commands interactively (reload picks up config changes)",
            "",
            "target (run/debug):",
            "  --policy <name>           run one policy (with its fault handler and the global fault handler)",
            "  --path <prefix>           run a listener path chain (default: resolve from --url)",
            "  --service <name>          HTTP service to resolve paths in",
            "  --chain k=v,...           explicit chain: globalRequest,request,routing,response,globalResponse",
            "                            (value is a policy, 'global' for the configured global policy, or 'none')",
            "request:",
            "  --request <file.yaml>     request spec (method, path, headers, body, attributes)",
            "  -X <method>  --url <path?query>  -H 'Name: value'  --data <text>  --data-file <file>",
            "  --content-type <type>     --attr name=value",
            "  --mock <routes.yaml>      virtual backends for routing filters (url, method, status, headers, body,",
            "                            delayMs, error: refused|timeout). Unmocked destinations fail like a refused connection",
            "  --real-routing            use the native HTTP client against local mock servers (needs an unrestricted license)",
            "  --allow-network <h,...>   let Java-level clients (LDAP, JDBC, SMTP...) reach these hosts ('all' disables the guard)",
            "debug:",
            "  -b <filter|policy:name|abort|fault>   breakpoint (repeatable)",
            "  --step                    stop before the first filter",
            "  --force '<filter>=true|false|abort'  override a filter outcome (repeatable, run/debug/test)",
            "configuration:",
            "  --config <path>           .fed, .pol, deployed group dir (configs.xml), YAML store, or store URL",
            "  --env <file.env>          environment package merged with a .pol, as projdeploy does",
            "  --env-props <file>        envSettings.props for ${env.X} values (repeatable, later wins)",
            "  --passphrase <p>          configuration passphrase",
            "options:",
            "  --modules +A,-B           force-load / skip loadable modules",
            "  --json                    machine-readable output",
            "  --no-diff                 skip per-filter attribute diffs (faster)",
            "  --quiet                   only the summary line");

    public static void main(String[] argv) {
        int code;
        try {
            code = run(new Args(argv));
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            code = 2;
        } catch (Throwable t) {
            System.err.println("error: " + GatewayRuntime.describe(t));
            t.printStackTrace();
            code = 1;
        }
        System.out.flush();
        Runtime.getRuntime().halt(code);
    }

    /** A booted sandbox: runtime + index, reusable across commands (shell mode). */
    static final class Session {
        final Args boot;
        GatewayRuntime rt;
        PolicyIndex index;
        double bootMs;

        Session(Args boot) throws Exception {
            this.boot = boot;
            load();
        }

        void load() throws Exception {
            GatewayRuntime.Options o = new GatewayRuntime.Options();
            o.config = boot.opt("--config", System.getenv("APD_CONFIG"));
            if (o.config == null) {
                throw new IllegalArgumentException("--config is required (or set APD_CONFIG)");
            }
            o.envPackage = boot.opt("--env", System.getenv("APD_ENV"));
            o.passphrase = boot.opt("--passphrase", System.getenv("APD_PASSPHRASE"));
            o.envProps.addAll(boot.opts("--env-props"));
            for (String m : boot.opts("--modules")) {
                for (String part : m.split(",")) {
                    part = part.trim();
                    if (part.startsWith("+")) {
                        o.include.add(part.substring(1));
                    } else if (part.startsWith("-")) {
                        o.exclude.add(part.substring(1));
                    }
                }
            }
            if (rt != null) {
                rt.close();
            }
            long t0 = System.nanoTime();
            // Every filter runs through DebugProcessor: needed for forced outcomes and the sandbox network.
            rt = new GatewayRuntime(o);
            index = new PolicyIndex(rt);
            bootMs = (System.nanoTime() - t0) / 1_000_000.0;
        }
    }

    static final String VERSION = "1.0.0";

    static int run(Args a) throws Exception {
        if (a.flag("--version")) {
            System.out.printf("apimgr-policy-debugger %s on %s %s (%s)%n", VERSION, System.getProperty("java.vendor"),
                    System.getProperty("java.version"), System.getProperty("java.home"));
            return 0;
        }
        if (a.command == null || a.flag("--help") || a.flag("-h")) {
            System.out.println(USAGE);
            return a.command == null ? 2 : 0;
        }
        NetworkGuard.install(a.opts("--allow-network"));
        Session s = new Session(a);
        if (a.command.equals("shell")) {
            return shell(s);
        }
        return dispatch(s, a);
    }

    /** Boots once, then runs commands read from stdin: run/debug/test/policies/paths/info/reload/exit. */
    static int shell(Session s) throws Exception {
        System.out.printf("apimgr-policy-debugger shell — %d policies, %d paths, booted in %.0f ms. Type 'help' or 'exit'.%n",
                s.index.byPath.size(), s.index.paths.size(), s.bootMs);
        java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(System.in,
                java.nio.charset.StandardCharsets.UTF_8));
        int last = 0;
        while (true) {
            System.out.print(Report.c("36", "apd> "));
            System.out.flush();
            String line = in.readLine();
            if (line == null) {
                return last;
            }
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.equals("exit") || line.equals("quit")) {
                return last;
            }
            if (line.equals("help")) {
                System.out.println("shell commands: reload | exit | any command below without --config"
                        + USAGE.substring(USAGE.indexOf('\n')));
                continue;
            }
            try {
                if (line.equals("reload")) {
                    s.load();
                    System.out.printf("reloaded in %.0f ms%n", s.bootMs);
                    continue;
                }
                last = dispatch(s, new Args(tokenize(line)));
            } catch (IllegalArgumentException e) {
                System.out.println("error: " + e.getMessage());
                last = 2;
            } catch (Throwable t) {
                System.out.println("error: " + GatewayRuntime.describe(t));
                last = 1;
            }
        }
    }

    static String[] tokenize(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        boolean any = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    cur.append(c);
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
                any = true;
            } else if (Character.isWhitespace(c)) {
                if (cur.length() > 0 || any) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    any = false;
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0 || any) {
            out.add(cur.toString());
        }
        return out.toArray(new String[0]);
    }

    static int dispatch(Session s, Args a) throws Exception {
        GatewayRuntime rt = s.rt;
        PolicyIndex index = s.index;
        double bootMs = s.bootMs;
        boolean realRouting = a.flag("--real-routing") || s.boot.flag("--real-routing");
        PrintStream out = System.out;
        if (a.command == null) {
            throw new IllegalArgumentException("missing command");
        }

        switch (a.command) {
            case "doctor":
                return doctor(s);
            case "info":
                return info(rt, index, bootMs, a.flag("--json"), a.flag("-v"));
            case "policies": {
                String filter = a.positional.isEmpty() ? null : a.positional.get(0).toLowerCase();
                for (PolicyIndex.Policy p : index.byPath.values()) {
                    if (filter == null || p.path.toLowerCase().contains(filter)) {
                        out.println(p.path);
                    }
                }
                return 0;
            }
            case "paths":
                for (PolicyIndex.RelativePath p : index.paths) {
                    PolicyIndex.Policy main = index.byPk(p.entity.getReferenceValue("filterCircuit"));
                    out.printf("%-28s %-8s %-60s -> %s%n", p.service, p.httpMethod, p.uriprefix,
                            main == null ? "(none)" : main.path);
                }
                return 0;
            case "run":
            case "debug": {
                Lifecycle.Plan plan = plan(a);
                RequestSpec req = request(a);
                ExecutionTrace trace = new ExecutionTrace(!a.flag("--no-diff"));
                Debugger dbg = new Debugger(a.opts("-b"), a.flag("--step"), a.opts("--force"), a.command.equals("debug"));
                dbg.install(trace);
                List<MockBackend.Route> mocks = new ArrayList<>();
                for (String mf : a.opts("--mock")) {
                    mocks.addAll(YAML.readValue(new File(mf),
                            new com.fasterxml.jackson.core.type.TypeReference<List<MockBackend.Route>>() { }));
                }
                // relative bodyFile paths resolve against the --request file, else the working directory
                String reqFile = a.opt("--request", null);
                File baseDir = reqFile == null ? new File(".") : new File(reqFile).getAbsoluteFile().getParentFile();
                Lifecycle.Result r = new Lifecycle(rt, index).run(plan, req, baseDir, trace, mocks, realRouting);
                Report.print(r, a.flag("--json"), a.flag("--quiet"), out);
                return r.outcome.equals("pass") ? 0 : 3;
            }
            case "test": {
                if (a.positional.isEmpty()) {
                    throw new IllegalArgumentException("test needs at least one case file or directory");
                }
                return new TestRunner(rt, index, a.flag("--no-diff"), realRouting).run(a.positional, a.opt("--junit", null),
                        a.flag("--json"), a.flag("-v"), out);
            }
            default:
                throw new IllegalArgumentException("unknown command: " + a.command + "\n\n" + USAGE);
        }
    }

    /** Environment self-check: runtime, modules, sandbox isolation. */
    private static int doctor(Session s) {
        int problems = 0;
        System.out.printf("%-34s %s%n", "java runtime", System.getProperty("java.vendor") + " " + System.getProperty("java.version")
                + " (" + System.getProperty("java.home") + ")");
        System.out.printf("%-34s %d policies, %d paths, boot %.0f ms%n", "configuration", s.index.byPath.size(),
                s.index.paths.size(), s.bootMs);
        long failed = s.rt.modules.values().stream().filter(m -> m.state.equals("failed")).count();
        long loaded = s.rt.modules.values().stream().filter(m -> m.state.equals("loaded")).count();
        System.out.printf("%-34s %d loaded, %d failed (see `info -v`)%n", "loadable modules", loaded, failed);
        problems += failed > 0 ? 1 : 0;
        boolean global = com.vordel.circuit.GlobalProperties.getInstance() != null;
        System.out.printf("%-34s %s%n", "global policies (GlobalProperties)", global ? "available" : "MISSING");
        problems += global ? 0 : 1;
        String probe = "example.com";
        String guard;
        try (java.net.Socket sock = new java.net.Socket()) {
            sock.connect(new java.net.InetSocketAddress(probe, 80), 2000);
            guard = Report.c("33", "OPEN") + " (outbound Java connections allowed)";
        } catch (SecurityException e) {
            guard = Report.c("32", "isolated") + " (connection to " + probe + ":80 blocked)";
        } catch (Exception e) {
            guard = "no guard, network unreachable: " + GatewayRuntime.describe(e);
        }
        System.out.printf("%-34s %s%n", "sandbox network (Java clients)", guard);
        System.out.printf("%-34s %s%n", "routing filters", "virtual backend (mocks); --real-routing needs an unrestricted license");
        System.out.printf("%-34s %s%n", "private /etc/hosts namespace",
                System.getenv("APD_HOSTS_FILE") != null ? "active" : "inactive (only needed for --real-routing)");
        return problems == 0 ? 0 : 5;
    }

    private static int info(GatewayRuntime rt, PolicyIndex index, double bootMs, boolean json, boolean verbose) throws Exception {
        if (json) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("store", rt.storeUrl);
            m.put("envSettingsApplied", rt.envSettingsApplied);
            m.put("envProps", rt.envPropsLoaded);
            m.put("bootMillis", bootMs);
            m.put("policies", index.byPath.size());
            m.put("paths", index.paths.size());
            m.put("modules", rt.modules.values());
            System.out.println(JSON.writeValueAsString(m));
            return 0;
        }
        System.out.printf("store     %s%nenv      EnvSettingsStore %s; props %s%nboot      %.0f ms%npolicies  %d%npaths     %d%nmodules:%n",
                rt.storeUrl, rt.envSettingsApplied ? "applied" : "not present",
                rt.envPropsLoaded.isEmpty() ? "none" : rt.envPropsLoaded, bootMs, index.byPath.size(), index.paths.size());
        for (GatewayRuntime.ModuleStatus s : rt.modules.values()) {
            System.out.printf("  %-8s %-36s %6d ms  %s%n", s.state, s.type, s.millis, s.error == null ? "" : s.error);
            if (verbose && s.where != null) {
                System.out.println("           at " + s.where);
            }
        }
        return 0;
    }

    static Lifecycle.Plan plan(Args a) {
        Lifecycle.Plan p = new Lifecycle.Plan();
        p.policy = a.opt("--policy", null);
        p.path = a.opt("--path", null);
        p.service = a.opt("--service", null);
        String chain = a.opt("--chain", null);
        if (chain != null) {
            p.chain = new LinkedHashMap<>();
            for (String kv : chain.split(",")) {
                String[] parts = kv.split("=", 2);
                if (parts.length != 2) {
                    throw new IllegalArgumentException("--chain expects slot=policy pairs, got: " + kv);
                }
                p.chain.put(parts[0].trim(), parts[1].trim());
            }
        }
        return p;
    }

    static RequestSpec request(Args a) throws Exception {
        String file = a.opt("--request", null);
        RequestSpec r = file == null ? new RequestSpec() : YAML.readValue(new File(file), RequestSpec.class);
        r.method = a.opt("-X", r.method);
        r.path = a.opt("--url", r.path);
        r.body = a.opt("--data", r.body);
        r.bodyFile = a.opt("--data-file", r.bodyFile);
        r.contentType = a.opt("--content-type", r.contentType);
        for (String h : a.opts("-H")) {
            int i = h.indexOf(':');
            if (i < 1) {
                throw new IllegalArgumentException("-H expects 'Name: value', got: " + h);
            }
            r.headers.put(h.substring(0, i).trim(), h.substring(i + 1).trim());
        }
        for (String kv : a.opts("--attr")) {
            int i = kv.indexOf('=');
            if (i < 1) {
                throw new IllegalArgumentException("--attr expects name=value, got: " + kv);
            }
            r.attributes.put(kv.substring(0, i), kv.substring(i + 1));
        }
        return r;
    }

    /** Minimal option parser: repeatable --opt value, boolean flags, positionals. */
    static final class Args {
        static final Set<String> FLAGS = new HashSet<>(Arrays.asList(
                "--json", "--no-diff", "--quiet", "--step", "--help", "-h", "-v", "--real-routing", "--version"));
        final String command;
        final Map<String, List<String>> options = new LinkedHashMap<>();
        final List<String> positional = new ArrayList<>();

        Args(String[] argv) {
            String cmd = null;
            for (int i = 0; i < argv.length; i++) {
                String s = argv[i];
                if (s.startsWith("-") && !FLAGS.contains(s)) {
                    if (i + 1 >= argv.length) {
                        throw new IllegalArgumentException("missing value for " + s);
                    }
                    options.computeIfAbsent(s, k -> new ArrayList<>()).add(argv[++i]);
                } else if (s.startsWith("-")) {
                    options.computeIfAbsent(s, k -> new ArrayList<>()).add("true");
                } else if (cmd == null) {
                    cmd = s;
                } else {
                    positional.add(s);
                }
            }
            this.command = cmd;
        }

        String opt(String name, String def) {
            List<String> v = options.get(name);
            return v == null ? def : v.get(v.size() - 1);
        }

        List<String> opts(String name) {
            return options.getOrDefault(name, new ArrayList<>());
        }

        boolean flag(String name) {
            return options.containsKey(name);
        }
    }
}
