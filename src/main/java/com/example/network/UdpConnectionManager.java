package com.example.network;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

import com.example.Utils;

/**
 * UDP hole punching connection manager.
 */
public class UdpConnectionManager {
    private static final Logger logger = System.getLogger(UdpConnectionManager.class.getName());
    private static final int USHORT_MAX_VALUE = (1 << 16) - 1;
    private static DatagramChannel channel;
    private static Thread receiver;
    private static boolean active = false;
    private static Map<InetSocketAddress, UdpConnection> connections = new HashMap<>();

    public static class UdpConnection {
        private final Logger logger = System.getLogger(this.getClass().getName());
        private boolean active = false;
        private long interval;
        private long timeout;
        private long lastReceive = 0;
        private Thread keepalive;
        private final InetSocketAddress host;
        private Supplier<ByteBuffer> generator = () -> ByteBuffer.allocate(0).flip();
        private Runnable onConnectEventListener;
        private Runnable onDisconnectEventListener;
        private Consumer<byte[]> onReceiveEventListener;
        private Status status;

        public enum Status {
            Connecting("⛅"),
            Connected("☀"),
            Disconnected("☔");
            private String icon;
            public String getIcon() {
                return this.icon;
            }
            private Status(String icon) {
                this.icon = icon;
            }
        }

        public UdpConnection(InetSocketAddress host) {
            this.host = host;

            this.interval = Long.getLong("udp.interval", 1_000);
            this.timeout = Long.getLong("udp.timeout", this.interval * 3);
            
            logger.log(Level.INFO, "udp keepalive interval : {0}", interval);
            logger.log(Level.INFO, "udp keepalive timeout  : {0}", timeout);
        }

        public InetSocketAddress getHost() {
            return this.host;
        }

        public Status getStatus() {
            return this.status;
        }

        public void setStatus(Status status) {
            this.status = status;
        }

        public UdpConnection config(long interval, long timeout) {
            // TODO: check value
            this.interval = interval;
            this.timeout = timeout;
            return this;
        }

        public UdpConnection generator(Supplier<ByteBuffer> generator) {
            this.generator = generator;
            return this;
        }

        public UdpConnection onConnect(Runnable onConnectEventListener) {
            this.onConnectEventListener = onConnectEventListener;
            return this;
        }

        public UdpConnection onDisconnect(Runnable onDisconnectEventListener) {
            this.onDisconnectEventListener = onDisconnectEventListener;
            return this;
        }

        public UdpConnection onReceive(Consumer<byte[]> onReceiveEventListener) {
            this.onReceiveEventListener = onReceiveEventListener;
            return this;
        }

        public void send(ByteBuffer data) throws IOException {
            logger.log(Level.INFO, "send data to {0} ({1}bit)", this.host, data.limit());
            channel.send(data, this.host);
        }

        private void receive(ByteBuffer buffer) {
            this.status = Status.Connected;

            if (this.lastReceive == 0) {
                if (this.onConnectEventListener != null) {
                    // TODO: 例外、別Thread
                    this.onConnectEventListener.run();
                }
            }

            this.lastReceive = new Date().getTime();

            int size = buffer.flip().limit();
            if (size == 0) {
                logger.log(Level.DEBUG, "receive keepalive from {0}", Utils.format(this.host));
            } else {
                logger.log(Level.DEBUG, "receive data from {0} ({1}bit)", Utils.format(this.host), size);
                byte[] data = new byte[size];
                buffer.get(data);
                if (this.onReceiveEventListener != null) {
                    // TODO: 例外、別Thread
                    this.onReceiveEventListener.accept(data);
                }
            }
        }

        public void start() {
            if (this.active) return;
            logger.log(Level.INFO, "start udp keepalive to {0}", Utils.format(host));

            this.keepalive = new Thread(() -> {
                long lastKeepalive = 0;
                while (this.active) {
                    long now = new Date().getTime();
                    if (now - lastKeepalive > this.interval) {
                        try {
                            logger.log(Level.DEBUG, "send udp keepalive to {0}", Utils.format(host));
                            ByteBuffer data = this.generator.get();
                            channel.send(data, host);
                        } catch (Exception e) {
                            logger.log(Level.ERROR, "udp keepalive send error", e);
                        }
                        lastKeepalive = now;
                    }
                    if (this.lastReceive != 0 && now - this.lastReceive > this.timeout) {
                        if (this.onDisconnectEventListener != null) {
                            logger.log(Level.WARNING, "udp keepalive timeout : {0}", Utils.format(this.host));
                            this.lastReceive = 0;
                            this.status = Status.Connecting;
                            // TODO: 例外、別Thread
                            this.onDisconnectEventListener.run();
                        }
                    }
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        // pass
                    }
                }
            }, String.format("UDP Keepalive(%s)", Utils.format(host)));

            this.active = true;
            this.status = Status.Connecting;
            this.keepalive.start();
        }

        public void stop() throws InterruptedException, IOException {
            if (!this.active) return;
            logger.log(Level.INFO, "stop udp keepalive to {0}", Utils.format(host));
            this.active = false;
            this.keepalive.join();
            this.status = Status.Disconnected;
        }
    }

    public static UdpConnection add(InetSocketAddress host) {
        logger.log(Level.INFO, "add host : {0}", host);
        UdpConnection connection = new UdpConnection(host);
        connections.put(host, connection);
        return connection;
    }

    public static UdpConnection get(InetSocketAddress host) {
        return connections.containsKey(host) ? connections.get(host) : null;
    }

    public static Integer getPort() {
        return channel.socket().getLocalPort();
    }

    public static void start() throws SocketException, IOException {
        if (active) return;
        logger.log(Level.INFO, "start udp receiver");
        
        if (channel == null) {
            channel = DatagramChannel.open();
            channel.configureBlocking(false);
        }

        receiver = new Thread(() -> {
            while (active) {
                try {
                    ByteBuffer buffer = ByteBuffer.allocate(USHORT_MAX_VALUE);
                    SocketAddress socket = channel.receive(buffer);
                    if (socket instanceof InetSocketAddress addr) {
                        if (connections.containsKey(addr)) {
                            connections.get(addr).receive(buffer);
                        }
                    }
                    Thread.sleep(1);
                } catch (Exception e) {
                    logger.log(Level.ERROR, "data receive error", e);
                }
            }
        }, "UDP Receiver");

        active = true;
        receiver.start();
        for (UdpConnection connection: connections.values()) {
            connection.start();
        }
    }

    public static void stop() throws InterruptedException, IOException {
        if (!active) return;
        logger.log(Level.INFO, "stop udp receiver");
        active = false;
        for (UdpConnection connection: connections.values()) {
            connection.stop();
        }
        receiver.join();
        channel.close();
        channel = null;
    }
}
