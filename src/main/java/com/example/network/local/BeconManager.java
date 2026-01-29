package com.example.network.local;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.example.Utils;

/**
 * Multicast becon manager.
 */
public class BeconManager {
    private final int USHORT_MAX_VALUE = (1 << 16) - 1;
    private final Integer p2pPort;
    private final InetAddress multicastAddr;
    private final Integer multicastPort;
    private final long interval;
    private final DatagramChannel channel;
    private Thread sender;
    private Thread receiver;
    private boolean active = false;
    private Consumer<InetSocketAddress> onReceiveEventListener;

    public BeconManager(int p2pPort) throws IOException {
        this.p2pPort = p2pPort;
        this.multicastAddr = InetAddress.getByName(System.getProperty("multicast.addr", "224.0.0.1"));
        this.multicastPort = Integer.getInteger("multicast.port", 12345);
        this.interval = Long.getLong("multicast.interval", 1000);

        this.channel = DatagramChannel.open();
        this.channel.configureBlocking(false);
        this.channel.socket().bind(new InetSocketAddress(this.multicastPort));

        for (NetworkInterface nic: this.getSiteLocalNetworkInterfaces().keySet()) {
            this.channel.join(this.multicastAddr, nic);
        }
    }

    public int getP2pPort() {
        return p2pPort;
    }

    public InetAddress getMulticastAddr() {
        return multicastAddr;
    }

    public int getMulticastPort() {
        return multicastPort;
    }

    public long getInterval() {
        return interval;
    }

    public BeconManager onReceive(Consumer<InetSocketAddress> onReceiveEventListener) {
        this.onReceiveEventListener = onReceiveEventListener;
        return this;
    }

    public void start() throws SocketException, IOException {
        if (this.active) return;

        this.sender = new Thread(() -> {
            InetSocketAddress addr = new InetSocketAddress(this.multicastAddr, this.multicastPort);
            long last = 0;
            while (this.active) {
                try {
                    long now = new Date().getTime();
                    if (now - last > this.interval) {
                        ByteBuffer data = ByteBuffer
                            .allocate(Short.BYTES)
                            .putShort(this.p2pPort.shortValue())
                            .flip();
                        this.channel.send(data, addr);
                        last = now;
                    }
                    Thread.sleep(1);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });

        this.receiver = new Thread(() -> {
            while (this.active) {
                try {
                    ByteBuffer buffer = ByteBuffer.allocate(USHORT_MAX_VALUE);
                    SocketAddress socket = this.channel.receive(buffer);
                    if (socket instanceof InetSocketAddress addr) {
                        boolean self = this.getSiteLocalNetworkInterfaces().values().stream().anyMatch(
                            addrs -> addrs.contains(addr.getAddress().getHostAddress())
                        );
                        if (!self) {
                            int size = buffer.flip().limit();
                            if (size == Short.BYTES) {
                                int port = Utils.ushort2int(buffer.getShort());
                                InetSocketAddress host = new InetSocketAddress(addr.getAddress(), port);
                                if (this.onReceiveEventListener != null) {
                                    // TODO: 例外、別Thread
                                    this.onReceiveEventListener.accept(host);
                                }
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
        this.sender.start();
    }

    public void stop() throws InterruptedException, IOException {
        if (!this.active) return;
        this.active = false;
        this.sender.join();
        this.receiver.join();
        this.channel.close();
    }

    private Map<NetworkInterface, List<String>> getSiteLocalNetworkInterfaces() throws SocketException {
        Map<NetworkInterface, List<String>> nics = new HashMap<>();
        for (NetworkInterface nic: Collections.list(NetworkInterface.getNetworkInterfaces())) {
            for (InterfaceAddress addr: nic.getInterfaceAddresses()) {
                if (addr.getAddress().isSiteLocalAddress()) {
                    if (!nics.containsKey(nic)) nics.put(nic, new ArrayList<>());
                    nics.get(nic).add(addr.getAddress().getHostAddress());
                }
            }
        }
        return nics;
    }
}
