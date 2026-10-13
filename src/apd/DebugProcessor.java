package apd;

import com.vordel.circuit.CircuitAbortException;
import com.vordel.circuit.Filter;
import com.vordel.circuit.FilterContainerImpl;
import com.vordel.circuit.Message;
import com.vordel.circuit.MessageProcessor;
import com.vordel.config.Circuit;
import com.vordel.config.ConfigContext;
import com.vordel.dwe.ServiceCircuitCache;
import com.vordel.es.ESPK;
import com.vordel.es.Entity;
import com.vordel.es.EntityStoreException;

/**
 * Wraps every filter's real MessageProcessor. It shares the real Filter, so the
 * success/failure graph walked by InvocationEngine is untouched; it only lets
 * the debugger override the outcome of a filter before it runs.
 */
public final class DebugProcessor extends MessageProcessor {

    /** Outcome decided by the debugger for a filter invocation. */
    public enum Outcome { RUN, TRUE, FALSE, ABORT }

    public interface OutcomeOracle {
        Outcome decide(Circuit circuit, DebugProcessor node, Message m);

        String abortMessage(DebugProcessor node);
    }

    static volatile OutcomeOracle oracle;
    /** Sandbox network: when set, routing filters are served by the virtual backend instead of the wire. */
    static volatile VirtualBackend virtualBackend;

    final MessageProcessor delegate;

    DebugProcessor(MessageProcessor delegate) {
        this.delegate = delegate;
    }

    public MessageProcessor delegate() {
        return delegate;
    }

    @Override
    public void filterAttached(ConfigContext ctx, Entity entity) throws EntityStoreException {
        super.filterAttached(ctx, entity);
        delegate.filterAttached(ctx, entity);
    }

    @Override
    public void filterDetached() {
        delegate.filterDetached();
    }

    @Override
    public boolean invoke(Circuit circuit, Message m) throws CircuitAbortException {
        OutcomeOracle o = oracle;
        Outcome outcome = o == null ? Outcome.RUN : o.decide(circuit, this, m);
        switch (outcome) {
            case TRUE:
                return true;
            case FALSE:
                return false;
            case ABORT:
                throw new CircuitAbortException(o.abortMessage(this));
            default:
                VirtualBackend vb = virtualBackend;
                if (vb != null && vb.handles(delegate)) {
                    return vb.invoke(circuit, (com.vordel.circuit.net.ConnectionProcessor) delegate, m);
                }
                return delegate.invoke(circuit, m);
        }
    }

    @Override
    public String toString() {
        return delegate.toString();
    }

    /** Circuit cache that hands out wrapped processors; plugs into ESSolutionPack. */
    static final class Cache extends ServiceCircuitCache {
        @Override
        public FilterContainerImpl create(Filter f, ESPK circuitPK) throws InstantiationException, IllegalAccessException, ClassNotFoundException {
            FilterContainerImpl real = super.create(f, circuitPK);
            if (!(real instanceof MessageProcessor)) {
                return real;
            }
            DebugProcessor wrapper = new DebugProcessor((MessageProcessor) real);
            wrapper.setFilter(f);
            return wrapper;
        }
    }
}
