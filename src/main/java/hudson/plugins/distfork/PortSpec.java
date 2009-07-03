package hudson.plugins.distfork;

import java.io.Serializable;

/**
 * Port forwarding specification.
 *
 * @author Kohsuke Kawaguchi
 */
public final class PortSpec implements Serializable {
    public final int receivingPort;
    public final String forwardingHost;
    public final int forwardingPort;

    public PortSpec(int receivingPort, String forwardingHost, int forwardingPort) {
        this.receivingPort = receivingPort;
        this.forwardingHost = forwardingHost;
        this.forwardingPort = forwardingPort;
    }
}
