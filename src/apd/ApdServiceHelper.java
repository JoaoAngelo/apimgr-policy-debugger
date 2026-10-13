package apd;

import com.vordel.circuit.CircuitTracker;
import com.vordel.circuit.Message;
import com.vordel.common.crypto.PasswordCipher;
import com.vordel.config.Circuit;
import com.vordel.dwe.WorkItem;
import com.vordel.es.ESPK;
import com.vordel.es.EntityStore;
import com.vordel.es.EntityStoreException;
import com.vordel.es.util.ShorthandKeyFinder;
import com.vordel.precipitate.ESSolutionPack;
import com.vordel.runtime.interfaces.AccessLogService;
import com.vordel.runtime.interfaces.ServiceHelper;
import com.vordel.runtime.interfaces.ServiceInfo;
import com.vordel.runtime.interfaces.TrafficMonitorListener;
import com.vordel.security.openssl.UI;

import java.io.IOException;
import java.net.URL;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Stand-in for com.vordel.runtime.ServiceHelperImpl, which is hard-wired to the
 * NetService singleton. Everything policy execution needs is backed by the
 * apd solution pack; gateway-wide side channels (traffic monitor, access
 * log, remote host management) are deliberately inert.
 */
final class ApdServiceHelper implements ServiceHelper {
    private final GatewayRuntime runtime;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    volatile ESSolutionPack pack;

    ApdServiceHelper(GatewayRuntime runtime) {
        this.runtime = runtime;
    }

    public void active() {
    }

    public void inactive() {
    }

    public boolean isGlobalMetricsMonitoringEnabled() {
        return false;
    }

    public boolean isRuntimeMonitoringEnabled() {
        return false;
    }

    public String maintenanceOngoing() {
        return null;
    }

    public void createRemoteHostsFromStrings(ShorthandKeyFinder shkf, Collection<String> urls) {
    }

    public EntityStore getStore() {
        return runtime.store;
    }

    public Lock getReadLock() {
        return lock.readLock();
    }

    public void addWorkItem(WorkItem item) {
        Thread t = new Thread(item::run, "apd-work-item");
        t.setDaemon(true);
        t.start();
    }

    public ServiceInfo getServiceInfo() {
        return null;
    }

    public ClassLoader getClassLoader(EntityStore store, ESPK pk) throws EntityStoreException {
        return getClassLoader(store, pk, ApdServiceHelper.class.getClassLoader());
    }

    public ClassLoader getClassLoader(EntityStore store, ESPK pk, ClassLoader defaultLoader) throws EntityStoreException {
        return defaultLoader;
    }

    public CircuitTracker getCircuitTracker(Message m) {
        return null;
    }

    public PasswordCipher getPasswordCipher() {
        return runtime.cipher;
    }

    public AccessLogService getAccessLogger() {
        return null;
    }

    public TrafficMonitorListener getTrafficMonitorMessageListener() {
        return null;
    }

    public Circuit getLocalPackCircuit(ESPK pk) {
        return pack.getCircuit(pk);
    }

    public void removeRemoteHost(String id) {
    }

    public void updateRemoteHost(Map<String, String[]> rhostConfig, Map<String, String[]> loadBalancerConfig,
                                 List<Map<String, String[]>> addressRankConfig, Map<String, String[]> watchdogConfig,
                                 List<Map<String, String[]>> responseCodeIntervals) {
    }

    public boolean checkEmtEnabled() {
        return false;
    }

    public void notifyEmtTransactionComplete(URL requestUrl) {
    }

    public String fileExtensionToMimeType(String extension) {
        return null;
    }

    public UI getUI(String engineName, String conversationName) {
        return null;
    }

    public String dumpCircuitPathPaths(Message message, boolean insideArray) throws IOException {
        return "[]";
    }

    public void logCircuitPathPaths(Message message) throws IOException {
    }
}
