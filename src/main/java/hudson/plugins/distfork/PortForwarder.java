package hudson.plugins.distfork;

import hudson.remoting.Callable;
import hudson.remoting.Channel;
import hudson.remoting.RemoteOutputStream;
import hudson.remoting.SocketInputStream;
import hudson.remoting.SocketOutputStream;
import hudson.remoting.VirtualChannel;
import hudson.util.StreamCopyThread;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;
import java.net.ServerSocket;
import java.net.Socket;
import static java.util.logging.Level.FINE;
import java.util.logging.Logger;

/**
 * Port forwarder over a remote channel.
 *
 * @author Kohsuke Kawaguchi
 */
public class PortForwarder extends Thread implements Closeable {
    private final Connector connector;
    private final ServerSocket socket;

    public PortForwarder(int localPort, Connector connector) throws IOException {
        super(String.format("Port forwarder %d",localPort));
        this.connector = connector;
        this.socket = new ServerSocket(localPort);
    }

    @Override
    public void run() {
        try {
            try {
                while(true) {
                    final Socket s = socket.accept();
                    new Thread("Port forwarding session from "+s.getRemoteSocketAddress()) {
                        public void run() {
                            try {
                                final OutputStream out = connector.connect(new RemoteOutputStream(new SocketOutputStream(s)));
                                new StreamCopyThread("Copier for "+s.getRemoteSocketAddress(),
                                    s.getInputStream(), out);
                            } catch (IOException e) {
                                // this happens if the socket connection is terminated abruptly.
                                LOGGER.log(FINE,"Port forwarding session was shut down abnormally",e);
                            }
                        }
                    }.start();
                }
            } finally {
                socket.close();
            }
        } catch (IOException e) {
            LOGGER.log(FINE,"Port forwarding was shut down abnormally",e);
        }
    }

    /**
     * Shuts down this port forwarder.
     */
    public void close() throws IOException {
        interrupt();
        socket.close();
    }

    /**
     * Starts a {@link PortForwarder} accepting remotely at the given channel,
     * which connects by using the given connector.
     *
     * @return
     *      A {@link Closeable} that can be used to shut the port forwarding down.
     */
    public static Closeable create(VirtualChannel ch, final int acceptingPort, Connector connector) throws IOException, InterruptedException {
        // need a remotable reference
        final Connector proxy = ch.export(Connector.class,connector);

        return ch.call(new Callable<Closeable,IOException>() {
            public Closeable call() throws IOException {
                PortForwarder t = new PortForwarder(acceptingPort, proxy);
                t.start();
                return Channel.current().export(Closeable.class,t);
            }
        });
    }

    static abstract class Connector implements Serializable {
        /**
         * Establishes a port forwarding connection and returns
         * the writer end.
         *
         * @param out
         *      The writer end to the initiator. The callee will
         *      start a thread that writes to this.
         */
        public abstract OutputStream connect(OutputStream out) throws IOException;

        /**
         * Creates a proxy.
         */
        /*package*/ final Connector asProxy(Channel channel) {
            return channel.export(Connector.class,this);
        }

        /**
         * Creates a connector on the remote side that connects to the speicied host and port.
         */
        public static Connector create(VirtualChannel channel, String remoteHost, int remotePort) throws IOException, InterruptedException {
            return channel.call(new ConnectorExporter(remoteHost, remotePort));
        }
    }

    /**
     * Creates a remote {@link Connector} instance and returns it.
     */
    private static final class ConnectorExporter implements Callable<Connector,IOException> {
        private final String remoteHost;
        private final int remotePort;

        private ConnectorExporter(String remoteHost, int remotePort) {
            this.remoteHost = remoteHost;
            this.remotePort = remotePort;
        }

        public Connector call() throws IOException {
            return new Connector() {
                public OutputStream connect(OutputStream out) throws IOException {
                    Socket s = new Socket(remoteHost, remotePort);
                    new StreamCopyThread(String.format("Copier to %s:%d",remoteHost,remotePort),
                        new SocketInputStream(s), out);
                    return new RemoteOutputStream(new SocketOutputStream(s));
                }
            }.asProxy(Channel.current());
        }

        private static final long serialVersionUID = 1L;
    }

    private static final Logger LOGGER = Logger.getLogger(PortForwarder.class.getName());
}
