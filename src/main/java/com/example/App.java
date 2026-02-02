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
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

import com.example.network.UdpConnectionManager;
import com.example.network.UdpConnectionManager.UdpConnection;
import com.example.network.UdpConnectionManager.UdpConnection.Status;
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

    private static class UdpConnectionElement {
        private UdpConnection connection;
        private String history = "";
        public UdpConnection getConnection() {
            return connection;
        }
        public String getHistory() {
            return history;
        }
        public void setHistory(String history) {
            this.history = history;
        }
        public UdpConnectionElement(UdpConnection connection) {
            this.connection = connection;
        }
        @Override
        public String toString() {
            return Utils.format(this.connection.getHost());
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
    private DefaultListModel<UdpConnectionElement> hosts = new DefaultListModel<>();
    private JList<UdpConnectionElement> list = new JList<>(hosts);
    private JTextArea history = new JTextArea();
    private JTextField message = new JTextField();
    private JButton send = new JButton("Send");
    private JLabel info = new JLabel();
    private JPanel layout = new JPanel(new BorderLayout());
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

        this.list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        this.list.addListSelectionListener(e -> {
            UdpConnectionElement el = this.list.getSelectedValue();
            if (el != null) {
                history.setText(el.getHistory());
                boolean isConnected = Status.Connected.equals(el.getConnection().getStatus());
                message.setEnabled(isConnected);
                send.setEnabled(isConnected);
            } else {
                history.setText("");
                message.setEnabled(false);
                send.setEnabled(false);
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
        this.layout.add(new JScrollPane(list), BorderLayout.LINE_START);
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
                                UdpConnection connection = connect(addr);
                                hosts.addElement(new UdpConnectionElement(connection));
                                connection.start();
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
                UdpConnectionElement el = this.list.getSelectedValue();
                if (el != null && el.getConnection().getHost().equals(host)) {
                    this.message.setEnabled(true);
                    this.send.setEnabled(true);
                }
            }
        ).onDisconnect(
            () -> {
                UdpConnectionElement el = this.list.getSelectedValue();
                if (el != null && el.getConnection().getHost().equals(host)) {
                    this.message.setEnabled(false);
                    this.send.setEnabled(false);
                    this.history.setText("");
                }
            }
        ).onReceive(
            // message
            (data) -> {
                String now = this.formatter.format(new Date());
                Enumeration<UdpConnectionElement> enumeration = this.hosts.elements();
                while (enumeration.hasMoreElements()) {
                    UdpConnectionElement el = enumeration.nextElement();
                    if (el.getConnection().getHost().equals(host)) {
                        el.setHistory(String.format("%s%s -> %s\n", el.getHistory(), now, new String(data, CHARSET)));
                    }
                }
                UdpConnectionElement el = this.list.getSelectedValue();
                if (el != null && el.getConnection().getHost().equals(host)) {
                    this.history.setText(el.getHistory());
                }
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
                Enumeration<UdpConnectionElement> enumeration = this.hosts.elements();
                while (enumeration.hasMoreElements()) {
                    UdpConnectionElement el = enumeration.nextElement();
                    if (el.getConnection().getHost().equals(host)) {
                        return;
                    }
                }
                UdpConnection connection = connect(host);
                hosts.addElement(new UdpConnectionElement(connection));
                connection.start();
            }
        );
        this.becon.start();

        this.listen.put(NetworkArea.LAN, new InetSocketAddress("0.0.0.0", UdpConnectionManager.getPort()));
    }

    private void sendMessage() {
        try {
            UdpConnectionElement el = this.list.getSelectedValue();
            if (el != null) {
                el.getConnection().send(ByteBuffer.wrap(message.getText().getBytes(CHARSET)));
                String now = this.formatter.format(new Date());
                history.setText(String.format("%s%s <- %s\n", history.getText(), now, message.getText()));
                message.setText("");
                el.setHistory(this.history.getText());
            }
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
