package com.example.network;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.charset.Charset;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * UDP hole punching connection manager.
 */
public class UdpConnectionManager {
    private final Charset CHARSET = Charset.forName("utf-8");
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
    private BiConsumer<InetSocketAddress, String> onMessageEventListener;

    public UdpConnectionManager() throws IOException {
        this.interval = Long.getLong("udp.interval", 1000);
        this.timeout = Long.getLong("udp.timeout", this.interval * 3);
        
        this.channel = DatagramChannel.open();
        this.channel.configureBlocking(false);
    }

    public UdpConnectionManager onConnect(Consumer<InetSocketAddress> onConnectEventListener) {
        this.onConnectEventListener = onConnectEventListener;
        return this;
    }

    public UdpConnectionManager onDisconnect(Consumer<InetSocketAddress> onDisconnectEventListener) {
        this.onDisconnectEventListener = onDisconnectEventListener;
        return this;
    }

    public UdpConnectionManager onMessage(BiConsumer<InetSocketAddress, String> onMessageEventListener) {
        this.onMessageEventListener = onMessageEventListener;
        return this;
    }

    public Integer getPort() {
        return this.channel.socket().getLocalPort();
    }

    public void add(InetSocketAddress host) {
        this.hosts.put(host, null);
    }

    public void send(InetSocketAddress host, String message) throws IOException {
        this.channel.send(ByteBuffer.wrap(message.getBytes(CHARSET)), host);
    }

    public void start() throws SocketException, IOException {
        if (this.active) return;

        this.keepalive = new Thread(() -> {
            long last = 0;
            while (this.active) {
                for (InetSocketAddress host: this.hosts.keySet()) {
                    try {
                        long now = new Date().getTime();
                        if (now - last > this.interval) {
                            ByteBuffer data = ByteBuffer.allocate(0).flip();
                            this.channel.send(data, host);
                            last = now;
                        }
                        Thread.sleep(1);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        });

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
                            // keepalive
                        } else {
                            byte[] data = new byte[size];
                            buffer.get(data);
                            if (this.onMessageEventListener != null) {
                                // TODO: 例外、別Thread
                                this.onMessageEventListener.accept(addr, new String(data, CHARSET));
                            }
                        }
                    }
                    for (Entry<InetSocketAddress, Long> entry: this.hosts.entrySet()) {
                        if (entry.getValue() != null && now - entry.getValue() > this.timeout) {
                            if (this.onDisconnectEventListener != null) {
                                this.hosts.remove(entry.getKey());
                                // TODO: 例外、別Thread
                                this.onDisconnectEventListener.accept(entry.getKey());
                            }
                        }
                    }
                    Thread.sleep(1);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });

        this.active = true;
        this.receiver.start();
        this.keepalive.start();
    }

    public void stop() throws InterruptedException, IOException {
        if (!this.active) return;
        this.active = false;
        this.keepalive.join();
        this.receiver.join();
        this.channel.close();
    }
}
