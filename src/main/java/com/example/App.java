package com.example;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeSelectionModel;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

import com.example.network.UdpConnectionManager;
import com.example.network.UdpConnectionManager.UdpConnection;
import com.example.network.global.StunClient;
import com.example.network.local.BeconManager;

/**
 * Hello world!
 *
 */
public class App extends JFrame
{
    private final Logger logger = System.getLogger(this.getClass().getName());

    private enum NetworkArea {
        LAN,
        WAN;
    };

    private enum NetworkStatus {
        Connecting("⛅"),
        Connected("☀");
        private String icon;
        public String getIcon() {
            return this.icon;
        }
        private NetworkStatus(String icon) {
            this.icon = icon;
        }
    }

    private static class HostTreeNode extends DefaultMutableTreeNode {
        private final InetSocketAddress host;
        private NetworkStatus status;
        public InetSocketAddress getHost() {
            return this.host;
        }
        public NetworkStatus getStatus() {
            return this.status;
        }
        public void setStatus(NetworkStatus status) {
            this.status = status;
            this.setUserObject(String.format("%s(%s)", Utils.format(host), status.getIcon()));
        }
        public HostTreeNode(InetSocketAddress host, NetworkStatus status) {
            super();
            this.host = host;
            this.setStatus(status);
        }
    }

    // TODO: thread safe
    private static class JNodeTree extends JTree {
        private Map<NetworkArea, DefaultMutableTreeNode> networks = new HashMap<>() {{
            this.put(NetworkArea.LAN, new DefaultMutableTreeNode(NetworkArea.LAN.name()));
            this.put(NetworkArea.WAN, new DefaultMutableTreeNode(NetworkArea.WAN.name()));
        }};
        private DefaultTreeModel model = new DefaultTreeModel(new DefaultMutableTreeNode("Area") {{
            this.add(networks.get(NetworkArea.LAN));
            this.add(networks.get(NetworkArea.WAN));
        }}, false);
        public JNodeTree() {
            super();
            this.setModel(this.model);
        }
        public void add(InetSocketAddress host, NetworkArea area, NetworkStatus status) {
            Map<InetSocketAddress, HostTreeNode> mapping = this.getMapping(area);
            if (mapping.containsKey(host)) {
                this.update(host, area, status);
            } else {
                this.networks.get(area).add(new HostTreeNode(host, status));
                this.model.reload();
            }
        }
        public void update(InetSocketAddress host, NetworkArea area, NetworkStatus status) {
            Map<InetSocketAddress, HostTreeNode> mapping = this.getMapping(area);
            if (mapping.containsKey(host)) {
                mapping.get(host).setStatus(status);
            }
        }
        public Map<InetSocketAddress, HostTreeNode> getMapping(NetworkArea area) {
            return new HashMap<>() {{
                DefaultMutableTreeNode network = networks.get(area);
                for (int i = 0; i < network.getChildCount(); i++) {
                    if (network.getChildAt(i) instanceof HostTreeNode node) {
                        this.put(node.getHost(), node);
                    }
                }
            }};
        }
    }

    private final Charset CHARSET = Charset.forName("utf-8");
    private BeconManager becon;
    private final SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private final InetSocketAddress stun = new InetSocketAddress(
        System.getProperty("stun.server.addr", "stun.l.google.com"),
        Integer.getInteger("stun.server.port", 19302)
    );
    private final Thread refresh = new Thread(() -> {
        while (true) {
            Function<NetworkArea, String> format = area -> {
                InetSocketAddress listen = this.listen.containsKey(area) ? this.listen.get(area) : null;
                String value = Utils.format(listen);
                return value != null ? value : "-";
            }; 
            this.info.setText(String.format(
                " [Listen] LAN=%s, WAN=%s",
                format.apply(NetworkArea.LAN),
                format.apply(NetworkArea.WAN)
            ));
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                // pass
            }
        }
    }, "UI refresh");

    private JMenuBar menubar = new JMenuBar();
    private JNodeTree tree = new JNodeTree();
    private JTextArea history = new JTextArea();
    private JTextField message = new JTextField();
    private JButton send = new JButton("Send");
    private JLabel info = new JLabel();
    private JPanel layout = new JPanel(new BorderLayout());
    private InetSocketAddress selection = null;
    private Map<InetSocketAddress, String> cache = new HashMap<>();
    private Map<NetworkArea, InetSocketAddress> listen = new HashMap<>();
    private JDialog connectDialog = new JDialog(this, "Connect to", true);

    public App() throws IOException {
        super();
        this.setupUi();
        this.setupNetwork();
    }

    private void setupUi() {
        this.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        this.setSize(640, 480);
        this.setLocationRelativeTo(null);
        this.setTitle("java-udp-hole-punching-example");

        this.menubar.add(new JMenu("File") {{
            this.add(new JMenuItem("Connect") {{
                this.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        connectDialog.setVisible(true);
                    }
                });
            }});
            this.add(new JMenuItem("Exit") {{
                this.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        System.exit(0);
                    }
                });
            }});
        }});
        this.menubar.add(new JMenu("Edit") {{
            this.add(new JMenuItem("Copy STUN mapped address") {{
                this.addActionListener(e -> {
                    if (listen.containsKey(NetworkArea.WAN)) {
                        StringSelection value = new StringSelection(Utils.format(listen.get(NetworkArea.WAN)));
                        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(value, null);
                    }
                });
            }});
        }});

        this.tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        this.tree.addTreeSelectionListener(new TreeSelectionListener() {
            @Override
            public void valueChanged(TreeSelectionEvent e) {
                Object[] path = e.getPath().getPath();
                if (path.length == 3 && path[2] instanceof HostTreeNode node) {
                    selection = node.getHost();
                    if (!cache.containsKey(selection)) cache.put(selection, "");
                    history.setText(cache.get(selection));
                    boolean isConnected = NetworkStatus.Connected.equals(node.getStatus());
                    message.setEnabled(isConnected);
                    send.setEnabled(isConnected);
                } else {
                    selection = null;
                    history.setText("");
                    message.setEnabled(false);
                    send.setEnabled(false);
                }
            }
        });

        this.message.addKeyListener(new KeyListener() {
            @Override
            public void keyPressed(KeyEvent e) {
                // pass
            }
            @Override
            public void keyReleased(KeyEvent e) {
                // pass
            }
            @Override
            public void keyTyped(KeyEvent e) {
                if (e.getKeyChar() == '\n') {
                    sendMessage();
                }
            }
        });

        this.send.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                sendMessage();
            }
        });

        this.history.setBackground(new Color(224, 224, 224));
        this.history.setEditable(false);
        this.message.setEnabled(false);
        this.send.setEnabled(false);

        this.layout.add(menubar, BorderLayout.PAGE_START);
        this.layout.add(tree, BorderLayout.LINE_START);
        this.layout.add(new JPanel(new BorderLayout()) {{
            this.add(history, BorderLayout.CENTER);
            this.add(new JPanel(new BorderLayout()) {{
                this.add(message, BorderLayout.CENTER);
                this.add(send, BorderLayout.LINE_END);
            }}, BorderLayout.PAGE_END);
        }}, BorderLayout.CENTER);
        this.layout.add(info, BorderLayout.PAGE_END);
        this.getContentPane().add(this.layout);

        this.refresh.start();

        this.connectDialog.setLocationRelativeTo(null);
        this.connectDialog.setSize(320, 80);
        this.connectDialog.getContentPane().add(new JPanel(new BorderLayout()) {{
            JTextField input = new JTextField();
            this.add(input, BorderLayout.CENTER);
            this.add(new JButton("Connect") {{
                this.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        if (input.getText() != null && !"".equals(input.getText())) {
                            String[] parts = input.getText().split(":");
                            if (parts.length == 2) {
                                InetSocketAddress addr = new InetSocketAddress(parts[0], Integer.valueOf(parts[1]));
                                NetworkArea area = addr.getAddress().isSiteLocalAddress() ? NetworkArea.LAN : NetworkArea.WAN;
                                tree.add(addr, area, NetworkStatus.Connecting);
                                connect(addr).start();
                                input.setText("");
                                connectDialog.setVisible(false);
                            }
                        }
                    }
                });
            }}, BorderLayout.PAGE_END);
        }});
    }

    private UdpConnection connect(InetSocketAddress host) {
        return UdpConnectionManager.add(
            host
        ).onConnect(
            () -> {
                if (host.equals(stun)) return;
                NetworkArea area = host.getAddress().isSiteLocalAddress() ? NetworkArea.LAN : NetworkArea.WAN;
                this.tree.update(host, area, NetworkStatus.Connected);
                if (host.equals(this.selection)) {
                    this.message.setEnabled(true);
                    this.send.setEnabled(true);
                }
            }
        ).onDisconnect(
            () -> {
                if (host.equals(stun)) return;
                NetworkArea area = host.getAddress().isSiteLocalAddress() ? NetworkArea.LAN : NetworkArea.WAN;
                this.tree.update(host, area, NetworkStatus.Connecting);
            }
        ).onReceive(
            // message
            (data) -> {
                if (!this.cache.containsKey(host)) this.cache.put(host, "");
                String now = this.formatter.format(new Date());
                this.cache.put(host, String.format("%s%s -> %s\n", history.getText(), now, new String(data, CHARSET)));
                if (host.equals(this.selection)) history.setText(this.cache.get(host));
            }
        );
    }

    private void setupNetwork() throws IOException {
        long interval = Long.getLong("stun.interval", 3_000);
        long timeout = Long.getLong("stun.timeout", interval * 3);

        UdpConnectionManager.add(
            stun
        ).config(
            interval,
            timeout
        ).generator(
            () -> StunClient.generateRequest()
        ).onReceive(data -> {
            // stun response
            try {
                InetSocketAddress mapped = StunClient.parseResponse(ByteBuffer.wrap(data));
                this.listen.put(NetworkArea.WAN, mapped);
            } catch (UnknownHostException e) {
                // TODO: Dialog
                logger.log(Level.ERROR, "invalid stun response", e);
            }
        });
        UdpConnectionManager.start();

        this.becon = new BeconManager(
            UdpConnectionManager.getPort()
        ).onReceive(
            host -> {
                Map<InetSocketAddress, HostTreeNode> mapping = this.tree.getMapping(NetworkArea.LAN);
                if (!mapping.containsKey(host)) {
                    this.tree.add(host, NetworkArea.LAN, NetworkStatus.Connecting);
                    connect(host).start();
                }
            }
        );
        this.becon.start();

        this.listen.put(NetworkArea.LAN, new InetSocketAddress("0.0.0.0", UdpConnectionManager.getPort()));
    }

    private void sendMessage() {
        try {
            UdpConnectionManager.get(this.selection).send(ByteBuffer.wrap(message.getText().getBytes(CHARSET)));
            String now = this.formatter.format(new Date());
            history.setText(String.format("%s%s <- %s\n", history.getText(), now, message.getText()));
            message.setText("");
            this.cache.put(this.selection, this.history.getText());
        } catch (IOException e) {
            // TODO: Dialog
            logger.log(Level.ERROR, "message send error", e);
        }
    }

    public static void main(String[] args) throws IOException {
        App app = new App();
        app.setVisible(true);
    }
}
