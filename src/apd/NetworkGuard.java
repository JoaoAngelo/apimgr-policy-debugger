package apd;

import java.net.InetAddress;
import java.net.SocketPermission;
import java.security.Permission;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Sandbox isolation for Java-level networking (LDAP, JDBC, SMTP, JMS, Java HTTP
 * clients used by filters). Outbound connections are refused unless the target
 * is loopback or explicitly allowed. HTTP routing filters never reach this:
 * they are served by the virtual backend.
 */
final class NetworkGuard extends SecurityManager {

    static final Set<String> blocked = Collections.synchronizedSet(new TreeSet<>());
    private final List<Pattern> allowed = new ArrayList<>();
    /** Socket.connect checks the resolved address, so allowed hostnames are pre-resolved. */
    private final Set<String> allowedIps = new TreeSet<>();
    private final boolean allowAll;

    private NetworkGuard(List<String> allow) {
        boolean all = false;
        for (String a : allow) {
            for (String part : a.split(",")) {
                part = part.trim();
                if (part.equalsIgnoreCase("all")) {
                    all = true;
                } else if (!part.isEmpty()) {
                    allowed.add(Debugger.glob(part));
                    if (!part.contains("*")) {
                        String host = part.contains(":") && part.indexOf(':') == part.lastIndexOf(':')
                                ? part.substring(0, part.indexOf(':')) : part;
                        try {
                            for (InetAddress ia : InetAddress.getAllByName(host)) {
                                allowedIps.add(ia.getHostAddress());
                            }
                        } catch (Exception ignored) {
                            // unresolvable now; hostname match still applies
                        }
                    }
                }
            }
        }
        this.allowAll = all;
    }

    static void install(List<String> allow) {
        NetworkGuard g = new NetworkGuard(allow);
        if (!g.allowAll) {
            System.setSecurityManager(g);
        }
    }

    @Override
    public void checkPermission(Permission perm) {
        if (perm instanceof SocketPermission && perm.getActions().contains("connect")) {
            checkTarget(perm.getName());
        }
    }

    @Override
    public void checkPermission(Permission perm, Object context) {
        checkPermission(perm);
    }

    @Override
    public void checkConnect(String host, int port) {
        if (port != -1) {
            checkTarget(host + ":" + port);
        }
    }

    @Override
    public void checkConnect(String host, int port, Object context) {
        checkConnect(host, port);
    }

    private void checkTarget(String target) {
        String host = target;
        int colon = target.lastIndexOf(':');
        if (colon > 0 && target.indexOf(':') == colon) {
            host = target.substring(0, colon);
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.equals("localhost") || host.startsWith("127.") || host.equals("[::1]") || host.equals("::1")
                || host.equals("0:0:0:0:0:0:0:1")) {
            return;
        }
        if (allowedIps.contains(host)) {
            return;
        }
        for (Pattern p : allowed) {
            if (p.matcher(host).matches() || p.matcher(target).matches()) {
                return;
            }
        }
        try {
            if (InetAddress.getByName(host).isLoopbackAddress()) {
                return;
            }
        } catch (Exception ignored) {
            // unresolvable: block
        }
        blocked.add(target);
        throw new SecurityException("apimgr-policy-debugger sandbox: outbound connection to " + target
                + " blocked (use --allow-network " + host + ")");
    }
}
