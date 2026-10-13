package apd;

import com.vordel.circuit.CircuitServiceImpl;
import com.vordel.common.Dictionary;
import com.vordel.common.apiserver.service.CoreServiceRegistry;
import com.vordel.common.crypto.PasswordCipher;
import com.vordel.config.Circuit;
import com.vordel.config.LoadableModule;
import com.vordel.dwe.ServiceCircuitCache;
import com.vordel.el.Selector;
import com.vordel.es.ESPK;
import com.vordel.es.Entity;
import com.vordel.es.EntityStore;
import com.vordel.es.EntityStoreFactory;
import com.vordel.es.EntityType;
import com.vordel.es.Field;
import com.vordel.es.Value;
import com.vordel.es.util.ShorthandKeyFinder;
import com.vordel.precipitate.ESSolutionPack;
import com.vordel.archive.fed.DeploymentArchive;
import com.vordel.archive.fed.EnvironmentArchive;
import com.vordel.archive.fed.PolicyArchive;
import com.vordel.runtime.interfaces.CircuitService;
import com.vordel.store.util.env.EnvExpand;
import com.vordel.store.util.env.EnvExpandEntityStore;
import com.vordel.runtime.interfaces.ServiceHelper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Hosts the Axway policy engine in-process, without the NetService: loads the
 * entity store, builds the local solution pack and configures only the
 * loadable modules that policies need (no listeners, no background services).
 */
public final class GatewayRuntime {

    /** Modules that open sockets, start threads against external systems or replace the NetService. */
    static final Set<String> NETWORK_MODULES = new HashSet<>(Arrays.asList(
            "HTTP", "FileService", "JMSServiceGroup", "SolutionPackLoader", "AmaModule", "OPDbConfig",
            "RealtimeMonitoring", "ZeroDowntime", "ExternallyManagedTopology", "RendezvousDaemonGroup",
            "RemoteHost", "HSTSProfileGroup", "ParserFeatureGroup", "WAFProfileGroup", "SentinelServerGroup",
            "TrackedObjects", "AlertManager", "EventLog", "OpenTrafficEventLog", "AccessLogger",
            "OESSecurityRuntimeLoadableModule", "PassPortKeyStoreManager", "PassPortClientCache",
            // API Manager runtime: needs the licensed gateway's LicenseService and Cassandra.
            "PortalConfiguration"));

    final String storeUrl;
    final EntityStore store;
    final ShorthandKeyFinder skf;
    final PasswordCipher cipher;
    final ESSolutionPack pack;
    final Map<String, ModuleStatus> modules = new LinkedHashMap<>();
    private final List<LoadableModule> loadedModules = new ArrayList<>();

    public static final class ModuleStatus {
        public final String type;
        public final String state;
        public final long millis;
        public final String error;
        public String where;

        ModuleStatus(String type, String state, long millis, String error) {
            this.type = type;
            this.state = state;
            this.millis = millis;
            this.error = error;
        }

        ModuleStatus(String type, long millis, Throwable t) {
            this(type, isNativePeer(t) ? "native" : "failed", millis,
                    isNativePeer(t) ? "needs the gateway's native NetService (listener-side only)" : describe(t));
            Throwable root = t;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            StringBuilder sb = new StringBuilder();
            StackTraceElement[] st = root.getStackTrace();
            for (int i = 0; i < Math.min(4, st.length); i++) {
                sb.append(i == 0 ? "" : " <- ").append(st[i]);
            }
            this.where = sb.toString();
        }

        private static boolean isNativePeer(Throwable t) {
            return t instanceof NullPointerException && "null reference to native".equals(t.getMessage());
        }
    }

    /** What to load and how. */
    public static final class Options {
        public String config;                       // .fed, .pol, configs.xml dir, YAML store or store URL
        public String envPackage;                   // .env merged with a .pol (as projdeploy does)
        public String passphrase;
        public List<String> envProps = new ArrayList<>();   // envSettings.props files for ${env.X}
        public Set<String> include = new HashSet<>();
        public Set<String> exclude = new HashSet<>();
        public boolean instrument = true;
    }

    final List<String> envPropsLoaded = new ArrayList<>();
    boolean envSettingsApplied;

    public GatewayRuntime(Options o) throws Exception {
        this.storeUrl = resolveStoreUrl(o.config, o.envPackage, o.passphrase);
        registerSelectorNamespaces();
        loadEnvProperties(o.envProps);
        this.cipher = new PasswordCipher(o.passphrase == null ? new char[0] : o.passphrase.toCharArray());
        // Same as Service.initStore: environment-specific values from the EnvSettingsStore are applied
        // to the entities as they are read.
        EntityStore raw = EntityStoreFactory.getInstance().getEntityStoreForURL(storeUrl);
        this.envSettingsApplied = hasEnvSettings(storeUrl);
        this.store = new EnvExpandEntityStore(raw, cipher, envSettingsApplied);
        this.store.connect(storeUrl, new Properties());
        this.skf = new ShorthandKeyFinder(store);
        Set<String> include = o.include;
        Set<String> exclude = o.exclude;
        boolean instrument = o.instrument;

        ApdServiceHelper helper = new ApdServiceHelper(this);
        CoreServiceRegistry registry = CoreServiceRegistry.getInstance();
        registry.registerService(helper, ServiceHelper.class);
        registry.registerService(new CircuitServiceImpl(), CircuitService.class);

        this.pack = new ESSolutionPack(store, cipher, instrument ? new DebugProcessor.Cache() : new ServiceCircuitCache());
        helper.pack = pack;
        loadModules(include, exclude);
    }

    /**
     * Accepts a .fed, a .pol (optionally with a .env), a directory holding configs.xml, a YAML store
     * directory or a raw store URL. Archives are opened with the product's fed-archive classes.
     */
    static String resolveStoreUrl(String location, String envPackage, String passphrase) throws Exception {
        if (location.startsWith("federated:") || location.startsWith("yaml:")) {
            if (envPackage != null) {
                throw new IllegalArgumentException("--env only applies to a .pol policy package");
            }
            return location;
        }
        File f = new File(location).getAbsoluteFile();
        if (!f.exists()) {
            throw new IllegalArgumentException("configuration not found: " + location);
        }
        String name = f.getName().toLowerCase(java.util.Locale.ROOT);
        if (envPackage != null && !name.endsWith(".pol")) {
            throw new IllegalArgumentException("--env only applies to a .pol policy package");
        }
        if (f.isFile() && name.endsWith(".fed")) {
            File fed = f;
            f = extractArchive(List.of(fed), dir -> new DeploymentArchive(fed).extractContentToDir(dir));
        } else if (f.isFile() && name.endsWith(".pol")) {
            File pol = f;
            if (envPackage == null) {
                f = extractArchive(List.of(pol), dir -> new PolicyArchive(pol).extractContentToDir(dir));
            } else {
                File env = new File(envPackage).getAbsoluteFile();
                if (!env.isFile()) {
                    throw new IllegalArgumentException("environment package not found: " + envPackage);
                }
                // the merge projdeploy performs: policy package + environment package -> deployment
                f = extractArchive(List.of(pol, env), dir -> new DeploymentArchive(new PolicyArchive(pol),
                        new EnvironmentArchive(env), false, passphrase).extractContentToDir(dir));
            }
        } else if (f.isFile() && name.endsWith(".env")) {
            throw new IllegalArgumentException("a .env package holds no policies: pass the .pol with --config and the .env with --env");
        }
        if (f.isFile() && f.getName().equals("configs.xml")) {
            return "federated:file://" + f.getPath();
        }
        if (f.isDirectory()) {
            File configs = findConfigs(f);
            if (configs != null) {
                return "federated:file://" + configs.getPath();
            }
            if (new File(f, "META-INF").isDirectory() || new File(f, "_parent.yaml").isFile()) {
                return "yaml:file://" + f.getPath();
            }
        }
        throw new IOException("Unrecognised configuration: " + location
                + " (expected .fed, a directory with configs.xml, a YAML store or a federated:/yaml: URL)");
    }

    private static File findConfigs(File dir) {
        File direct = new File(dir, "configs.xml");
        if (direct.isFile()) {
            return direct;
        }
        File[] children = dir.listFiles(File::isDirectory);
        if (children != null) {
            for (File c : children) {
                File nested = new File(c, "configs.xml");
                if (nested.isFile()) {
                    return nested;
                }
            }
        }
        return null;
    }

    interface Extractor {
        void extractTo(File dir) throws Exception;
    }

    /** Extracts once per input content (name + size + mtime) into a cache directory. */
    private static File extractArchive(List<File> inputs, Extractor extractor) throws Exception {
        StringBuilder key = new StringBuilder();
        for (File in : inputs) {
            key.append(key.length() == 0 ? "" : "+").append(in.getName().replaceAll("[^A-Za-z0-9._-]", "_"))
                    .append('-').append(in.length()).append('-').append(in.lastModified());
        }
        // per user: a cache written by root is not listable by other users
        Path root = Files.createDirectories(new File(System.getProperty("java.io.tmpdir"),
                "apimgr-policy-debugger-" + System.getProperty("user.name") + "/archives").toPath());
        Path target = root.resolve(key.toString());
        if (!Files.isDirectory(target)) {
            Path staging = Files.createTempDirectory(root, "x");
            extractor.extractTo(staging.toFile());
            try {
                Files.move(staging, target);
            } catch (IOException raced) {
                // another process extracted the same archive first
            }
        }
        return target.toFile();
    }

    /** Same check as Service.hasEnvSettings: only federated stores that include an EnvSettingsStore. */
    private static boolean hasEnvSettings(String url) {
        if (!url.startsWith("federated:file://")) {
            return false;
        }
        try {
            String configs = Files.readString(new File(url.substring("federated:file://".length())).toPath());
            return configs.contains("EnvSettingsStore");
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * ${system.X} and ${env.X} come from system.properties and the instance's envSettings.props
     * (EnvExpand.reload). Extra --env-props files are merged on top, later files win.
     */
    private void loadEnvProperties(List<String> files) throws IOException {
        EnvExpand.reload();
        File own = new File(com.vordel.common.Config.getUserConf(), "envSettings.props");
        List<File> all = new ArrayList<>();
        if (own.isFile()) {
            all.add(own);
        }
        for (String f : files) {
            File file = new File(f).getAbsoluteFile();
            if (!file.isFile()) {
                throw new IllegalArgumentException("--env-props file not found: " + f);
            }
            all.add(file);
        }
        if (files.isEmpty()) {
            if (own.isFile()) {
                envPropsLoaded.add(own.getPath());
            }
            return;
        }
        StringBuilder merged = new StringBuilder();
        for (File file : all) {
            merged.append(Files.readString(file.toPath())).append('\n');
            envPropsLoaded.add(file.getPath());
        }
        Path tmp = Files.createTempFile("apimgr-policy-debugger-env", ".props");
        Files.writeString(tmp, merged.toString());
        tmp.toFile().deleteOnExit();
        new EnvExpand("env", tmp.toFile());
    }

    private static void registerSelectorNamespaces() {
        Selector.addGlobalNamespace("environment", new Dictionary() {
            public Object get(String key) {
                return System.getenv(key);
            }
        });
    }

    /** Mirrors SolutionPack.loadModules (load order, load then configure) restricted to policy-relevant modules. */
    private void loadModules(Set<String> include, Set<String> exclude) {
        Map<String, List<Entity>> byType = new LinkedHashMap<>();
        Map<String, Integer> order = new LinkedHashMap<>();
        Entity process = safeEntity("/[NetService]name=Service");
        List<Entity> candidates = new ArrayList<>();
        if (process != null) {
            candidates.addAll(entities(process.getPK(), "[LoadableModule]"));
        }
        candidates.addAll(entities(store.getRootPK(), "[LoadableModule]"));
        for (Entity e : candidates) {
            String type = e.getType().getName();
            byType.computeIfAbsent(type, k -> new ArrayList<>());
            if (byType.get(type).isEmpty()) {
                byType.get(type).add(e);
            }
            order.put(type, loadOrder(e.getType()));
        }
        List<String> types = new ArrayList<>(byType.keySet());
        types.sort((a, b) -> Integer.compare(order.get(a), order.get(b)));

        List<Object[]> loaded = new ArrayList<>();
        for (String type : types) {
            boolean wanted = include.contains(type) || (!NETWORK_MODULES.contains(type) && !exclude.contains(type));
            if (!wanted) {
                modules.put(type, new ModuleStatus(type, "skipped", 0, null));
                continue;
            }
            for (Entity e : byType.get(type)) {
                long t0 = System.currentTimeMillis();
                try {
                    String className = e.getStringValue("class");
                    LoadableModule m = Class.forName(className, true, Thread.currentThread().getContextClassLoader())
                            .asSubclass(LoadableModule.class).getDeclaredConstructor().newInstance();
                    m.load(null, type);
                    loaded.add(new Object[]{m, e, t0});
                } catch (Throwable t) {
                    ModuleStatus st = new ModuleStatus(type, System.currentTimeMillis() - t0, t);
                    if (st.state.equals("native") && type.equals("SystemSettings") && loadSettingStore(e)) {
                        st = new ModuleStatus(type, "loaded", System.currentTimeMillis() - t0,
                                "Java settings only (SettingStore); native process settings not applied");
                    }
                    modules.put(type, st);
                }
            }
        }
        for (Object[] l : loaded) {
            LoadableModule m = (LoadableModule) l[0];
            Entity e = (Entity) l[1];
            String type = e.getType().getName();
            long t0 = System.currentTimeMillis();
            ClassLoader prev = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(m.getClass().getClassLoader());
            try {
                m.configure(pack, e);
                loadedModules.add(m);
                modules.put(type, new ModuleStatus(type, "loaded", System.currentTimeMillis() - t0, null));
            } catch (Throwable t) {
                modules.put(type, new ModuleStatus(type, System.currentTimeMillis() - t0, t));
            } finally {
                Thread.currentThread().setContextClassLoader(prev);
            }
        }
    }

    /**
     * SystemSettings is a NativeModule whose Java half is a plain SettingStore read by filters and loggers
     * (date format, timeouts...). Populate that half from the configuration entity.
     */
    private boolean loadSettingStore(Entity e) {
        try {
            com.vordel.dwe.SettingStore store = new com.vordel.dwe.SettingStore();
            store.configure(pack, e);
            java.lang.reflect.Field base = com.vordel.dwe.SystemSettings.class.getDeclaredField("base");
            base.setAccessible(true);
            base.set(null, store);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static int loadOrder(EntityType t) {
        Field f = t.getConstantField("loadorder");
        if (f != null) {
            Value[] values = f.getValues();
            if (values != null && values.length > 0) {
                return Integer.parseInt(values[0].getData());
            }
        }
        return 1_000_000;
    }

    static String describe(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = root.getMessage();
        return root.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    }

    Entity safeEntity(String shorthand) {
        try {
            return skf.getEntity(shorthand);
        } catch (Exception e) {
            return null;
        }
    }

    List<Entity> entities(ESPK parent, String shorthand) {
        try {
            return skf.getEntities(parent, shorthand);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    List<Entity> entities(String shorthand) {
        try {
            return skf.getEntities(shorthand);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public Circuit circuit(ESPK pk) {
        return pack.getCircuit(pk);
    }

    /** Unloads modules in reverse order (shell reload). */
    void close() {
        for (int i = loadedModules.size() - 1; i >= 0; i--) {
            try {
                loadedModules.get(i).unload();
            } catch (Throwable ignored) {
                // best effort
            }
        }
        loadedModules.clear();
        try {
            pack.unloadCircuits();
            store.disconnect();
        } catch (Throwable ignored) {
            // best effort
        }
    }
}
