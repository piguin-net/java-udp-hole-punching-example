package com.example;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import javax.swing.JButton;
import javax.swing.JFrame;
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

import com.example.network.local.BeconManager;
import com.example.network.local.UdpConnectionManager;

/**
 * Hello world!
 *
 */
public class App extends JFrame
{
    private enum NetworkArea {
        LAN,
        WAN;
    };

    private enum NetworkStatus {
        Connecting("⛅"),
        Connected("☀"),
        Disconnected("☔");
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
            this.setUserObject(String.format("%s:%d(%s)", host.getAddress().getHostAddress(), host.getPort(), status.getIcon()));
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

    private final UdpConnectionManager udp;
    private final BeconManager becon;
    private final SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private JMenuBar menubar = new JMenuBar();
    private JNodeTree tree = new JNodeTree();
    private JTextArea history = new JTextArea();
    private JTextField message = new JTextField();
    private JButton send = new JButton("Send");
    private JPanel layout = new JPanel(new BorderLayout());
    private InetSocketAddress selection = null;
    private Map<InetSocketAddress, String> cache = new HashMap<>();

    private void sendMessage() {
        try {
            this.udp.send(this.selection, message.getText());
            String now = this.formatter.format(new Date());
            history.setText(String.format("%s%s <- %s\n", history.getText(), now, message.getText()));
            message.setText("");
            this.cache.put(this.selection, this.history.getText());
        } catch (IOException e) {
            // TODO: Dialog
            e.printStackTrace();
        }
    }

    public App() throws IOException {
        super();

        this.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        this.setSize(640, 480);
        this.setLocationRelativeTo(null);
        this.setTitle("java-udp-hole-punching-example");

        this.menubar.add(new JMenu("File") {{
            this.add(new JMenuItem("Exit") {{
                this.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        System.exit(0);
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
        this.getContentPane().add(this.layout);

        this.udp = new UdpConnectionManager().onConnect(
            host -> {
                this.tree.update(host, NetworkArea.LAN, NetworkStatus.Connected);
                if (host.equals(this.selection)) {
                    this.message.setEnabled(true);
                    this.send.setEnabled(true);
                }
            }
        ).onDisconnect(
            host -> this.tree.update(host, NetworkArea.LAN, NetworkStatus.Disconnected)
        ).onMessage(
            (host, message) -> {
                if (!this.cache.containsKey(host)) this.cache.put(host, "");
                String now = this.formatter.format(new Date());
                this.cache.put(host, String.format("%s%s -> %s\n", history.getText(), now, message));
                if (host.equals(this.selection)) history.setText(this.cache.get(host));
            }
        );
        this.udp.start();

        this.becon = new BeconManager(
            udp.getPort()
        ).onReceive(
            host -> {
                Map<InetSocketAddress, HostTreeNode> mapping = this.tree.getMapping(NetworkArea.LAN);
                if (mapping.containsKey(host)) {
                    if (NetworkStatus.Disconnected.equals(mapping.get(host).getStatus())) {
                        this.tree.update(host, NetworkArea.LAN, NetworkStatus.Connecting);
                    }
                } else {
                    this.tree.add(host, NetworkArea.LAN, NetworkStatus.Connecting);
                    this.udp.add(host);
                }
            }
        );
        this.becon.start();
    }

    public static void main(String[] args) throws IOException {
        App app = new App();
        app.setVisible(true);
    }
}
