import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * LanChat - peer-to-peer terminal chat + file transfer over LAN.
 *
 * Usage:
 *   java LanChat.java listen [port]          wait for a peer to connect (default port 5000)
 *   java LanChat.java connect <ip> [port]    connect to a waiting peer
 *   java LanChat.java scan                   find peers that are listening on the LAN
 *
 * In-chat commands:
 *   /send <path>   send a file
 *   /help          show commands
 *   /quit          disconnect
 */
public class LanChat {

    static final int DEFAULT_PORT = 5000;
    static final int DISCOVERY_PORT = 5001;
    static final String DISCOVER_MSG = "LANCHAT_DISCOVER";
    static final String REPLY_PREFIX = "LANCHAT_HERE:";

    static final byte TYPE_MSG = 1;
    static final byte TYPE_FILE = 2;
    static final byte TYPE_BYE = 3;

    static final Path RECEIVE_DIR = Paths.get("received");
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    // ANSI colours (work in most Linux/macOS terminals and Windows Terminal)
    static final String RESET = "\u001B[0m", GREEN = "\u001B[32m", CYAN = "\u001B[36m",
                        YELLOW = "\u001B[33m", RED = "\u001B[31m";

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final String myName;
    private String peerName = "peer";
    private volatile boolean running = true;

    public static void main(String[] args) throws Exception {
        if (args.length == 0) { usage(); return; }
        String name = System.getProperty("user.name", "me");

        switch (args[0]) {
            case "listen" -> {
                int port = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_PORT;
                ServerSocket server;
                try {
                    server = new ServerSocket(port);
                } catch (BindException e) {
                    error("Port " + port + " is already in use on this computer.");
                    error("(On macOS the AirPlay Receiver uses port 5000.) Try another port, e.g.:");
                    error("  java LanChat.java listen 5050     and your friend: connect <ip> 5050");
                    return;
                }
                Thread discovery = startDiscoveryResponder(name, port);
                try (server) {
                    info("Listening on " + localIps() + " port " + port + " ... waiting for a peer");
                    info("Your friend runs: java LanChat.java connect <one of the IPs above>"
                            + (port == DEFAULT_PORT ? "" : " " + port));
                    info("Make sure your firewall allows incoming TCP " + port + " (see README 'Troubleshooting').");
                    Socket s = server.accept();
                    discovery.interrupt();
                    new LanChat(s, name).run();
                }
            }
            case "connect" -> {
                if (args.length < 2) { usage(); return; }
                int port = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_PORT;
                info("Connecting to " + args[1] + ":" + port + " ...");
                Socket s = new Socket();
                try {
                    s.connect(new InetSocketAddress(args[1], port), 5000);
                } catch (SocketTimeoutException | NoRouteToHostException e) {
                    error("No answer from " + args[1] + ":" + port + ".");
                    error("Usually this means the other computer's FIREWALL is blocking TCP " + port
                            + ", or you are not on the same network (check both IPs start the same, e.g. 192.168.1.x).");
                    return;
                } catch (ConnectException e) {
                    error("Connection refused by " + args[1] + ":" + port + ".");
                    error("Nothing is listening there: ask your friend to run 'java LanChat.java listen"
                            + (port == DEFAULT_PORT ? "" : " " + port) + "' first, and check the IP and port.");
                    return;
                } catch (UnknownHostException e) {
                    error("Unknown host: " + args[1]);
                    return;
                }
                new LanChat(s, name).run();
            }
            case "scan" -> scan();
            default -> usage();
        }
    }

    LanChat(Socket socket, String myName) throws IOException {
        this.socket = socket;
        this.myName = myName;
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
    }

    void run() throws IOException {
        // Handshake: exchange names
        out.writeUTF(myName);
        out.flush();
        peerName = in.readUTF();
        info("Connected to " + peerName + " (" + socket.getInetAddress().getHostAddress() + ")");
        info("Type a message and press Enter. /send <file> to send a file, /help for commands.");

        Thread reader = new Thread(this::receiveLoop, "receiver");
        reader.setDaemon(true);
        reader.start();

        BufferedReader console = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while (running && (line = console.readLine()) != null) {
            line = line.strip();
            if (line.isEmpty()) continue;
            try {
                if (line.equals("/quit") || line.equals("/exit")) {
                    synchronized (out) { out.writeByte(TYPE_BYE); out.flush(); }
                    break;
                } else if (line.equals("/help")) {
                    info("/send <path>  send a file\n/quit         disconnect");
                } else if (line.startsWith("/send ")) {
                    String p = line.substring(6).strip();
                    if (p.length() > 1 && p.startsWith("\"") && p.endsWith("\"")) p = p.substring(1, p.length() - 1);
                    sendFileAsync(Paths.get(p.replaceFirst("^~", System.getProperty("user.home"))));
                } else {
                    synchronized (out) {
                        out.writeByte(TYPE_MSG);
                        out.writeUTF(line);
                        out.flush();
                    }
                }
            } catch (IOException e) {
                error("Connection lost: " + e.getMessage());
                break;
            }
        }
        shutdown();
    }

    private void receiveLoop() {
        try {
            while (running) {
                byte type = in.readByte();
                switch (type) {
                    case TYPE_MSG -> System.out.println(CYAN + "[" + now() + "] " + peerName + ": " + RESET + in.readUTF());
                    case TYPE_FILE -> receiveFile();
                    case TYPE_BYE -> { info(peerName + " left the chat."); shutdownAndExit(); return; }
                    default -> throw new IOException("Unknown message type " + type);
                }
            }
        } catch (EOFException | SocketException e) {
            if (running) { info(peerName + " disconnected."); shutdownAndExit(); }
        } catch (IOException e) {
            if (running) { error("Receive error: " + e.getMessage()); shutdownAndExit(); }
        }
    }

    // Files are sent on a background thread so you can keep chatting.
    // Writes are synchronized so a message never lands in the middle of a file.
    private void sendFileAsync(Path path) {
        if (!Files.isRegularFile(path)) { error("File not found: " + path); return; }
        new Thread(() -> {
            try {
                long size = Files.size(path);
                String fileName = path.getFileName().toString();
                info("Sending " + fileName + " (" + human(size) + ") ...");
                synchronized (out) {
                    out.writeByte(TYPE_FILE);
                    out.writeUTF(fileName);
                    out.writeLong(size);
                    try (InputStream fis = new BufferedInputStream(Files.newInputStream(path))) {
                        byte[] buf = new byte[64 * 1024];
                        long sent = 0; int n, lastPct = -1;
                        while ((n = fis.read(buf)) != -1) {
                            out.write(buf, 0, n);
                            sent += n;
                            int pct = size == 0 ? 100 : (int) (sent * 100 / size);
                            if (pct / 10 != lastPct / 10) { progress("Sending", pct); lastPct = pct; }
                        }
                    }
                    out.flush();
                }
                System.out.println();
                info("Sent " + fileName);
            } catch (IOException e) {
                error("File send failed: " + e.getMessage());
            }
        }, "sender").start();
    }

    private void receiveFile() throws IOException {
        String rawName = in.readUTF();
        long size = in.readLong();
        // Strip any path parts so a peer cannot write outside the received/ folder
        String fileName = Paths.get(rawName).getFileName().toString();
        Files.createDirectories(RECEIVE_DIR);
        Path target = uniquePath(RECEIVE_DIR.resolve(fileName));

        info(peerName + " is sending " + fileName + " (" + human(size) + ")");
        try (OutputStream fos = new BufferedOutputStream(Files.newOutputStream(target))) {
            byte[] buf = new byte[64 * 1024];
            long remaining = size; int lastPct = -1;
            while (remaining > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n == -1) throw new EOFException("Connection closed during file transfer");
                fos.write(buf, 0, n);
                remaining -= n;
                int pct = size == 0 ? 100 : (int) ((size - remaining) * 100 / size);
                if (pct / 10 != lastPct / 10) { progress("Receiving", pct); lastPct = pct; }
            }
        }
        System.out.println();
        info("Saved to " + target.toAbsolutePath());
    }

    // ---------- LAN discovery (UDP broadcast) ----------

    static Thread startDiscoveryResponder(String name, int tcpPort) {
        Thread t = new Thread(() -> {
            try (DatagramSocket ds = new DatagramSocket(null)) {
                ds.setReuseAddress(true);
                ds.bind(new InetSocketAddress(DISCOVERY_PORT));
                ds.setSoTimeout(1000);
                byte[] buf = new byte[256];
                while (!Thread.currentThread().isInterrupted()) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try { ds.receive(p); } catch (SocketTimeoutException e) { continue; }
                    String msg = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                    if (DISCOVER_MSG.equals(msg)) {
                        byte[] reply = (REPLY_PREFIX + name + ":" + tcpPort).getBytes(StandardCharsets.UTF_8);
                        ds.send(new DatagramPacket(reply, reply.length, p.getAddress(), p.getPort()));
                    }
                }
            } catch (IOException e) {
                // discovery is optional; ignore if the port is busy
            }
        }, "discovery");
        t.setDaemon(true);
        t.start();
        return t;
    }

    static void scan() throws IOException {
        info("Scanning LAN for listening peers (3 seconds) ...");
        try (DatagramSocket ds = new DatagramSocket()) {
            ds.setBroadcast(true);
            byte[] msg = DISCOVER_MSG.getBytes(StandardCharsets.UTF_8);
            for (InetAddress bcast : broadcastAddresses()) {
                try { ds.send(new DatagramPacket(msg, msg.length, bcast, DISCOVERY_PORT)); } catch (IOException ignored) { }
            }
            // Also ask every address on the local subnet directly. Replies to a broadcast are
            // dropped by stateful firewalls (ufw, Windows Defender) because they don't match the
            // outgoing packet; replies to a unicast probe do, so this works without opening ports
            // on the scanning machine.
            for (InetAddress host : subnetHosts()) {
                try { ds.send(new DatagramPacket(msg, msg.length, host, DISCOVERY_PORT)); } catch (IOException ignored) { }
            }
            byte[] buf = new byte[256];
            java.util.Set<String> seen = new java.util.HashSet<>();
            java.util.Set<String> mine = new java.util.HashSet<>(java.util.Arrays.asList(localIps().split(", ")));
            long end = System.currentTimeMillis() + 3000;
            while (true) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) break;
                ds.setSoTimeout((int) left);
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try { ds.receive(p); } catch (SocketTimeoutException e) { break; }
                String reply = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                if (!reply.startsWith(REPLY_PREFIX)) continue;
                String body = reply.substring(REPLY_PREFIX.length());
                int colon = body.lastIndexOf(':');
                if (colon < 0) continue;
                String peer = body.substring(0, colon), port = body.substring(colon + 1);
                String ip = p.getAddress().getHostAddress();
                if (!seen.add(ip + ":" + port)) continue;
                String note = mine.contains(ip) || p.getAddress().isLoopbackAddress() ? "  (this computer)" : "";
                System.out.println(GREEN + "  " + peer + "  ->  java LanChat.java connect " + ip + " " + port + note + RESET);
            }
            if (seen.isEmpty()) {
                info("No peers found. Ask your friend to run: java LanChat.java listen");
                info("If they are listening, their firewall is probably blocking UDP " + DISCOVERY_PORT
                        + " / TCP " + DEFAULT_PORT + " (see README 'Troubleshooting').");
            }
        }
    }

    /** All IPv4 host addresses on directly attached subnets of /22 or smaller (max ~1000 hosts each). */
    static java.util.List<InetAddress> subnetHosts() throws IOException {
        java.util.List<InetAddress> hosts = new java.util.ArrayList<>();
        for (NetworkInterface ni : java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!ni.isUp() || ni.isLoopback() || ni.isPointToPoint()) continue;
            for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                if (!(ia.getAddress() instanceof Inet4Address) || ia.getBroadcast() == null) continue;
                int prefix = ia.getNetworkPrefixLength();
                if (prefix < 22 || prefix > 30) continue;
                byte[] a = ia.getAddress().getAddress();
                int ip = ((a[0] & 0xff) << 24) | ((a[1] & 0xff) << 16) | ((a[2] & 0xff) << 8) | (a[3] & 0xff);
                int mask = -1 << (32 - prefix);
                int net = ip & mask, bcast = net | ~mask;
                for (int h = net + 1; h < bcast; h++) {
                    hosts.add(InetAddress.getByAddress(new byte[] {
                            (byte) (h >>> 24), (byte) (h >>> 16), (byte) (h >>> 8), (byte) h }));
                }
            }
        }
        return hosts;
    }

    static java.util.List<InetAddress> broadcastAddresses() throws IOException {
        java.util.List<InetAddress> list = new java.util.ArrayList<>();
        for (NetworkInterface ni : java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!ni.isUp() || ni.isLoopback()) continue;
            for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                if (ia.getBroadcast() != null) list.add(ia.getBroadcast());
            }
        }
        if (list.isEmpty()) list.add(InetAddress.getByName("255.255.255.255"));
        return list;
    }

    static String localIps() {
        StringBuilder sb = new StringBuilder();
        try {
            for (NetworkInterface ni : java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getAddress() instanceof Inet4Address) {
                        if (sb.length() > 0) sb.append(", ");
                        sb.append(ia.getAddress().getHostAddress());
                    }
                }
            }
        } catch (SocketException ignored) { }
        return sb.length() == 0 ? "0.0.0.0" : sb.toString();
    }

    // ---------- helpers ----------

    private void shutdown() {
        running = false;
        try { socket.close(); } catch (IOException ignored) { }
        info("Disconnected.");
    }

    private void shutdownAndExit() {
        shutdown();
        System.exit(0);
    }

    static Path uniquePath(Path p) {
        if (!Files.exists(p)) return p;
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; ; i++) {
            Path c = p.resolveSibling(base + "(" + i + ")" + ext);
            if (!Files.exists(c)) return c;
        }
    }

    static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] u = {"KB", "MB", "GB", "TB"};
        double v = bytes; int i = -1;
        do { v /= 1024; i++; } while (v >= 1024 && i < u.length - 1);
        return String.format("%.1f %s", v, u[i]);
    }

    static void progress(String label, int pct) {
        int bars = pct / 5;
        System.out.print("\r" + YELLOW + label + " [" + "#".repeat(bars) + " ".repeat(20 - bars) + "] " + pct + "%" + RESET);
        System.out.flush();
    }

    static String now() { return LocalTime.now().format(TIME); }
    static void info(String s) { System.out.println(GREEN + "* " + s + RESET); }
    static void error(String s) { System.out.println(RED + "! " + s + RESET); }

    static void usage() {
        System.out.println("""
            LanChat - LAN peer-to-peer chat and file transfer

              java LanChat.java listen [port]          wait for a peer (default port 5000)
              java LanChat.java connect <ip> [port]    connect to a peer
              java LanChat.java scan                   find listening peers on the LAN

            In chat:  /send <path>   /help   /quit
            Received files are saved in ./received/
            """);
    }
}
