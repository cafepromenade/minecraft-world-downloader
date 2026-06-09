package proxy;

import config.Config;
import game.NetworkMode;
import packets.DataReader;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicReference;

import static util.ExceptionHandling.attempt;

/**
 * Proxy server class, handles receiving of data and forwarding it to the right places.
 */
public class ProxyServer extends Thread {
    private final ConnectionDetails connectionDetails;
    private final ConnectionManager connectionManager;

    private DataReader onServerBoundPacket;
    private DataReader onClientBoundPacket;

    public ProxyServer(ConnectionManager connectionManager, ConnectionDetails connectionDetails) {
        this.connectionDetails = connectionDetails;
        this.connectionManager = connectionManager;
    }

    /**
     * Run the proxy server. This method does not return.
     * @param onServerBoundPacket data reader for client -> server traffic
     * @param onClientBoundPacket data reader for server -> client traffic
     */
    public void runServer(DataReader onServerBoundPacket, DataReader onClientBoundPacket) {
        this.onClientBoundPacket = onClientBoundPacket;
        this.onServerBoundPacket = onServerBoundPacket;
        this.start();
        this.setPriority(10);
    }

    @Override
    public void run() {
        setName("Proxy");
        
        String friendlyHost = connectionDetails.getFriendlyHost();
        System.out.println("Starting proxy for " + friendlyHost + ". Make sure to connect to localhost:" + connectionDetails.getPortLocal() + " instead of the regular server address.");

        // Developer mode: emit a diagnostics banner so toggling --dev-mode has a visible, useful effect
        // (it shows up directly in the web console's log pane). It also turns on per-connection logging
        // in the accept loop below.
        if (Config.isInDevMode()) {
            System.out.println("[dev] ===== developer mode enabled =====");
            System.out.println("[dev] " + System.getProperty("java.runtime.name", "java") + " " + System.getProperty("java.version")
                    + " on " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
            System.out.println("[dev] proxy: localhost:" + connectionDetails.getPortLocal() + "  ->  " + friendlyHost);
            System.out.println("[dev] world output dir: " + Config.getWorldOutputDir());
            System.out.println("[dev] features: extended-render-distance=" + Config.getExtendedRenderDistance()
                    + ", auto-open-containers=" + Config.autoOpenContainers()
                    + ", overview-map-render=" + Config.renderOverviewMap());
            System.out.println("[dev] verbose connection logging is ON - each client connection will be logged below.");
        }

        // Create a ServerSocket to listen for connections with
        AtomicReference<ServerSocket> ss = new AtomicReference<>();
        attempt(() -> ss.set(connectionDetails.getServerSocket()), (ex) -> {
            ex.printStackTrace();
            System.exit(1);
        });

        final byte[] request = new byte[4096];
        final byte[] reply = new byte[4096];

        while (true) {
            AtomicReference<Socket> client = new AtomicReference<>();
            AtomicReference<Socket> server = new AtomicReference<>();

            // Catch Throwable (not just Exception): an Error thrown while handling a packet would
            // otherwise unwind run() and silently kill the proxy thread, after which no packets are
            // forwarded and the client times out with no log. Catching here keeps the accept loop alive.
            try {
                // Wait for a connection on the local port
                client.set(ss.get().accept());
                if (Config.isInDevMode()) {
                    System.out.println("[dev] client connected from " + client.get().getRemoteSocketAddress());
                }

                // A new connection is starting: clear any state left over from the previous one. On a
                // CLEAN disconnect (EOF on either side) the read loops below fall through WITHOUT calling
                // reset() — only the error paths reset. Without this, the next connection inherits the
                // dead session's compression/encryption-enabled flags, half-read reader queues and
                // network mode, so the fresh (uncompressed, unencrypted) login/status gets misparsed: the
                // client fails with "DataFormatException: incorrect header check" and rejoining — or even
                // pinging the server from the list — hangs. Resetting here makes every connection start
                // from a clean slate. (setStreamToClient/Server below re-bind the new streams; reset()
                // does not touch them.)
                connectionManager.reset();

                final InputStream streamFromClient = client.get().getInputStream();
                final OutputStream streamToClient = client.get().getOutputStream();
                connectionManager.getEncryptionManager().setStreamToClient(streamToClient);

                // If the server cannot connect, close client connection
                attempt(() -> server.set(connectionDetails.getClientSocket()), (ex) -> {
                    System.err.println("Cannot connect to " + friendlyHost + ". The server may be down or on a different address. (" + ex.getClass().getCanonicalName() + ")");
                    attempt(client.get()::close);
                });

                final InputStream streamFromServer = server.get().getInputStream();
                final OutputStream streamToServer = server.get().getOutputStream();
                connectionManager.getEncryptionManager().setStreamToServer(streamToServer);

                // start client listener thread
                Thread clientListener = new Thread(() -> {
                    connectionManager.setMode(NetworkMode.HANDSHAKE);
                    try {
                        int bytesRead;
                        while ((bytesRead = streamFromClient.read(request)) != -1) {
                            onServerBoundPacket.pushData(request, bytesRead);
                        }
                        // clean EOF: the client's stream to us was closed normally
                        System.out.println("[disconnect] client closed the connection. Waiting for new connection...");
                    } catch (Throwable ex) {
                        // this thread reads FROM the client, so an error here is the CLIENT side dropping
                        if (isBenignClose(ex)) {
                            System.out.println("[disconnect] client closed the connection (" + ex.getMessage() + "). Waiting for new connection...");
                        } else {
                            System.out.println("[disconnect] client connection error: " + ex + " - waiting for new connection...");
                            ex.printStackTrace();
                        }
                        connectionManager.reset();
                    }
                    // the client closed the connection to us, so close our connection to the server.
                    attempt(streamToServer::close);
                }, "Proxy Client Listener");
                clientListener.start();
                clientListener.setPriority(10);

                // listen to messages from server
                try {
                    int bytesRead;
                    while ((bytesRead = streamFromServer.read(reply)) != -1) {
                        onClientBoundPacket.pushData(reply, bytesRead);
                    }
                    // clean EOF: the server closed its stream to us (often right after a kick / Disconnect)
                    System.out.println("[disconnect] server closed the connection. Waiting for new connection...");
                } catch (Throwable ex) {
                    // this thread reads FROM the server, so an error here is the SERVER side dropping
                    if (isBenignClose(ex)) {
                        System.out.println("[disconnect] server closed the connection (" + ex.getMessage() + "). Waiting for new connection...");
                    } else {
                        System.out.println("[disconnect] server connection error: " + ex + " - waiting for new connection...");
                        ex.printStackTrace();
                    }
                    connectionManager.reset();
                }

                // The server closed its connection to us, so we close our connection to our client.
                streamToClient.close();
            } catch (Throwable ex) {
                ex.printStackTrace();
                connectionManager.reset();
                if (server.get() != null) { attempt(server.get()::close); }
                if (client.get() != null) { attempt(client.get()::close); }
            }
        }
    }

    /**
     * Whether a read error is just a normal/abrupt socket close rather than a real fault. On Windows
     * especially, a peer closing the socket (incl. the routine server-list ping) surfaces as a
     * SocketException "Connection reset" instead of a clean EOF — that's not an error worth a stack trace.
     */
    private static boolean isBenignClose(Throwable ex) {
        String m = ex == null ? null : ex.getMessage();
        if (m == null) { return false; }
        m = m.toLowerCase();
        return m.contains("connection reset")
            || m.contains("socket closed")
            || m.contains("socket is closed")
            || m.contains("broken pipe")
            || m.contains("connection abort")
            || m.contains("an existing connection was forcibly closed")
            || m.contains("connection was aborted");
    }
}
