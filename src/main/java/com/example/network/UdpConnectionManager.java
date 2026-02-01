package com.example.network;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

import com.example.Utils;

/**
 * UDP hole punching connection manager.
 */
public class UdpConnectionManager {
    private final Logger logger = System.getLogger(this.getClass().getName());
    private final int USHORT_MAX_VALUE = (1 << 16) - 1;
    private final long interval;
    private final long timeout;
    private final DatagramChannel channel;
    private Thread keepalive;
    private Thread receiver;
    private boolean active = false;
    private Map<InetSocketAddress, Long> hosts = new HashMap<>();
    private Consumer<InetSocketAddress> onConnectEventListener;
    private Consumer<InetSocketAddress> onDisconnectEventListener;
    private BiConsumer<InetSocketAddress, byte[]> onReceiveDefaultEventListener;
    private Map<InetSocketAddress, Consumer<byte[]>> onReceiveEventListener = new HashMap<>();
    private Map<InetSocketAddress, Supplier<ByteBuffer>> generator = new HashMap<>();

    public UdpConnectionManager() throws IOException {
        this.interval = Long.getLong("udp.interval", 3_000);
        this.timeout = Long.getLong("udp.timeout", this.interval * 3);
        
        this.channel = DatagramChannel.open();
        this.channel.configureBlocking(false);

        logger.log(Level.INFO, "udp keepalive interval : {0}", interval);
        logger.log(Level.INFO, "udp keepalive timeout  : {0}", timeout);
    }

    public UdpConnectionManager onConnect(Consumer<InetSocketAddress> onConnectEventListener) {
        this.onConnectEventListener = onConnectEventListener;
        return this;
    }

    public UdpConnectionManager onDisconnect(Consumer<InetSocketAddress> onDisconnectEventListener) {
        this.onDisconnectEventListener = onDisconnectEventListener;
        return this;
    }

    public UdpConnectionManager onReceive(BiConsumer<InetSocketAddress, byte[]> onReceiveDefaultEventListener) {
        this.onReceiveDefaultEventListener = onReceiveDefaultEventListener;
        return this;
    }

    public UdpConnectionManager onReceive(InetSocketAddress host, Consumer<byte[]> onReceiveEventListener) {
        this.onReceiveEventListener.put(host, onReceiveEventListener);
        return this;
    }

    public Integer getPort() {
        return this.channel.socket().getLocalPort();
    }

    public void add(InetSocketAddress host) {
        logger.log(Level.INFO, "add p2p peer : {0}", host);
        this.hosts.put(host, null);
    }

    public void add(InetSocketAddress host, Supplier<ByteBuffer> generator) {
        logger.log(Level.INFO, "add stun server : {0}", host);
        this.generator.put(host, generator);
        this.hosts.put(host, null);
    }

    public void send(InetSocketAddress host, ByteBuffer data) throws IOException {
        logger.log(Level.INFO, "send data to {0} ({1}bit)", host, data.limit());
        this.channel.send(data, host);
    }

    public void start() throws SocketException, IOException {
        if (this.active) return;
        logger.log(Level.INFO, "start udp connection manager");

        this.keepalive = new Thread(() -> {
            long last = 0;
            while (this.active) {
                long now = new Date().getTime();
                if (now - last > this.interval) {
                    for (InetSocketAddress host: new HashSet<>(this.hosts.keySet())) {
                        try {
                            logger.log(Level.DEBUG, "send keepalive to {0}", Utils.format(host));
                            ByteBuffer data = this.generator.containsKey(host)
                                ? this.generator.get(host).get()
                                : ByteBuffer.allocate(0).flip();
                            this.channel.send(data, host);
                        } catch (Exception e) {
                            logger.log(Level.ERROR, "keepalive send error", e);
                        }
                    }
                    last = now;
                }
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    // pass
                }
            }
        }, "UDP Keepalive");

        this.receiver = new Thread(() -> {
            while (this.active) {
                try {
                    long now = new Date().getTime();
                    ByteBuffer buffer = ByteBuffer.allocate(USHORT_MAX_VALUE);
                    SocketAddress socket = this.channel.receive(buffer);
                    if (socket instanceof InetSocketAddress addr) {
                        if (!this.hosts.containsKey(addr) || this.hosts.get(addr) == null) {
                            if (this.onConnectEventListener != null) {
                                // TODO: 例外、別Thread
                                this.onConnectEventListener.accept(addr);
                            }
                        }
                        this.hosts.put(addr, now);

                        int size = buffer.flip().limit();
                        if (size == 0) {
                            logger.log(Level.DEBUG, "receive keepalive from {0}", Utils.format(addr));
                        } else {
                            logger.log(Level.DEBUG, "receive data from {0} ({1}bit)", Utils.format(addr), size);
                            byte[] data = new byte[size];
                            buffer.get(data);
                            if (this.onReceiveEventListener.containsKey(addr)) {
                                // TODO: 例外、別Thread
                                this.onReceiveEventListener.get(addr).accept(data);
                            } else {
                                if (this.onReceiveEventListener != null) {
                                    // TODO: 例外、別Thread
                                    this.onReceiveDefaultEventListener.accept(addr, data);
                                }
                            }
                        }
                    }
                    for (Entry<InetSocketAddress, Long> entry: new HashSet<>(this.hosts.entrySet())) {
                        if (entry.getValue() != null && now - entry.getValue() > this.timeout) {
                            if (this.onDisconnectEventListener != null) {
                                logger.log(Level.WARNING, "keepalive timeout : {0}", Utils.format(entry.getKey()));
                                this.hosts.put(entry.getKey(), null);
                                // TODO: 例外、別Thread
                                this.onDisconnectEventListener.accept(entry.getKey());
                            }
                        }
                    }
                    Thread.sleep(1);
                } catch (Exception e) {
                    logger.log(Level.ERROR, "data receive error", e);
                }
            }
        }, "UDP Receiver");

        this.active = true;
        this.receiver.start();
        this.keepalive.start();
    }

    public void stop() throws InterruptedException, IOException {
        if (!this.active) return;
        logger.log(Level.INFO, "stop udp connection manager");
        this.active = false;
        this.keepalive.join();
        this.receiver.join();
        this.channel.close();
    }
}
