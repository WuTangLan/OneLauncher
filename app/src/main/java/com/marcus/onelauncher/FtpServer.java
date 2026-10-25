package com.marcus.onelauncher;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Writer;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small, dependency-free FTP server (passive mode, binary transfers).
 * Plain Java so it has no Android dependencies and can be tested on any JVM.
 *
 * Supports: USER PASS SYST FEAT OPTS PWD XPWD CWD XCWD CDUP TYPE MODE STRU PASV EPSV
 * LIST NLST RETR STOR APPE REST DELE MKD XMKD RMD XRMD RNFR RNTO SIZE MDTM NOOP QUIT.
 */
public final class FtpServer {

    public interface Listener {
        /** A client finished uploading a file. */
        void onUpload(File file);
        /** Any command was received (used for the idle timer). */
        void onActivity();
    }

    private final File root;
    private final int port;
    private final String user;
    private final String pass;
    private final Listener listener;

    private volatile ServerSocket server;
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();

    public FtpServer(File root, int port, String user, String pass, Listener listener) {
        this.root = root;
        this.port = port;
        this.user = user;
        this.pass = pass;
        this.listener = listener;
    }

    public boolean isRunning() {
        ServerSocket s = server;
        return s != null && !s.isClosed();
    }

    public synchronized void start() throws IOException {
        if (isRunning()) return;
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(port));
        server = s;
        Thread t = new Thread(() -> acceptLoop(s), "ftp-accept");
        t.setDaemon(true);
        t.start();
    }

    public synchronized void stop() {
        closeQuietly(server);
        server = null;
        for (Socket c : clients) closeQuietly(c);
        clients.clear();
    }

    private void acceptLoop(ServerSocket s) {
        while (!s.isClosed()) {
            try {
                Socket c = s.accept();
                clients.add(c);
                Thread t = new Thread(() -> {
                    try {
                        new Session(c).run();
                    } finally {
                        clients.remove(c);
                        closeQuietly(c);
                    }
                }, "ftp-client");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (s.isClosed()) return;
            }
        }
    }

    // ------------------------------------------------------------------ session

    private final class Session {
        private final Socket ctrl;
        private PrintWriter out;
        private boolean loggedIn;
        private String pendingUser;
        private String cwd = "/";
        private ServerSocket pasv;
        private long restOffset;
        private File renameFrom;

        Session(Socket ctrl) { this.ctrl = ctrl; }

        void run() {
            try {
                ctrl.setSoTimeout(10 * 60 * 1000);
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(ctrl.getInputStream(), StandardCharsets.UTF_8));
                out = new PrintWriter(new OutputStreamWriter(ctrl.getOutputStream(), StandardCharsets.UTF_8), true);
                reply(220, "One Launcher FTP ready");
                String line;
                while ((line = in.readLine()) != null) {
                    if (listener != null) listener.onActivity();
                    int sp = line.indexOf(' ');
                    String cmd = (sp < 0 ? line : line.substring(0, sp)).trim().toUpperCase(Locale.US);
                    String arg = sp < 0 ? "" : line.substring(sp + 1);
                    if (!handle(cmd, arg)) break;
                }
            } catch (IOException ignored) {
            } finally {
                closeQuietly(pasv);
            }
        }

        private void reply(int code, String msg) {
            out.print(code + " " + msg + "\r\n");
            out.flush();
        }

        /** @return false to close the connection */
        private boolean handle(String cmd, String arg) throws IOException {
            switch (cmd) {
                case "USER":
                    pendingUser = arg;
                    loggedIn = false;
                    reply(331, "Password required");
                    return true;
                case "PASS":
                    if (user.equals(pendingUser) && pass.equals(arg)) {
                        loggedIn = true;
                        reply(230, "Logged in");
                    } else {
                        reply(530, "Login incorrect");
                    }
                    return true;
                case "QUIT":
                    reply(221, "Bye");
                    return false;
                case "NOOP":
                    reply(200, "OK");
                    return true;
                case "SYST":
                    reply(215, "UNIX Type: L8");
                    return true;
                case "FEAT":
                    out.print("211-Features:\r\n UTF8\r\n PASV\r\n EPSV\r\n SIZE\r\n MDTM\r\n REST STREAM\r\n211 End\r\n");
                    out.flush();
                    return true;
                case "OPTS":
                    reply(200, "OK");
                    return true;
            }

            if (!loggedIn) {
                reply(530, "Please log in");
                return true;
            }

            switch (cmd) {
                case "PWD":
                case "XPWD":
                    reply(257, "\"" + cwd.replace("\"", "\"\"") + "\" is the current directory");
                    break;
                case "CWD":
                case "XCWD": {
                    String target = virtualPath(arg);
                    File f = resolve(target);
                    if (f != null && f.isDirectory()) {
                        cwd = target;
                        reply(250, "Directory changed");
                    } else {
                        reply(550, "No such directory");
                    }
                    break;
                }
                case "CDUP":
                    cwd = virtualPath("..");
                    reply(250, "Directory changed");
                    break;
                case "TYPE":
                case "MODE":
                case "STRU":
                    reply(200, "OK");
                    break;
                case "PASV":
                    openPassive(false);
                    break;
                case "EPSV":
                    openPassive(true);
                    break;
                case "PORT":
                case "EPRT":
                    reply(502, "Active mode not supported, use passive mode");
                    break;
                case "LIST":
                case "NLST":
                    list(arg, cmd.equals("NLST"));
                    break;
                case "REST":
                    try {
                        restOffset = Long.parseLong(arg.trim());
                        reply(350, "Restarting at " + restOffset);
                    } catch (NumberFormatException e) {
                        reply(501, "Bad offset");
                    }
                    break;
                case "RETR":
                    retrieve(arg);
                    break;
                case "STOR":
                    store(arg, false);
                    break;
                case "APPE":
                    store(arg, true);
                    break;
                case "DELE": {
                    File f = resolve(virtualPath(arg));
                    if (f != null && f.isFile() && f.delete()) reply(250, "Deleted");
                    else reply(550, "Delete failed");
                    break;
                }
                case "MKD":
                case "XMKD": {
                    String vp = virtualPath(arg);
                    File f = resolve(vp);
                    if (f != null && f.mkdirs()) reply(257, "\"" + vp + "\" created");
                    else reply(550, "Create failed");
                    break;
                }
                case "RMD":
                case "XRMD": {
                    File f = resolve(virtualPath(arg));
                    if (f != null && !f.equals(canonicalRoot()) && f.isDirectory() && f.delete())
                        reply(250, "Removed");
                    else reply(550, "Remove failed (folder must be empty)");
                    break;
                }
                case "RNFR": {
                    File f = resolve(virtualPath(arg));
                    if (f != null && f.exists()) {
                        renameFrom = f;
                        reply(350, "Ready for RNTO");
                    } else {
                        reply(550, "No such file");
                    }
                    break;
                }
                case "RNTO": {
                    File to = resolve(virtualPath(arg));
                    if (renameFrom != null && to != null && renameFrom.renameTo(to)) reply(250, "Renamed");
                    else reply(550, "Rename failed");
                    renameFrom = null;
                    break;
                }
                case "SIZE": {
                    File f = resolve(virtualPath(arg));
                    if (f != null && f.isFile()) reply(213, String.valueOf(f.length()));
                    else reply(550, "No such file");
                    break;
                }
                case "MDTM": {
                    File f = resolve(virtualPath(arg));
                    if (f != null && f.exists()) {
                        SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
                        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
                        reply(213, fmt.format(new Date(f.lastModified())));
                    } else {
                        reply(550, "No such file");
                    }
                    break;
                }
                default:
                    reply(502, "Command not implemented");
            }
            return true;
        }

        // ---- passive data connections

        private void openPassive(boolean extended) throws IOException {
            closeQuietly(pasv);
            InetAddress local = ctrl.getLocalAddress();
            pasv = new ServerSocket(0, 1, local);
            pasv.setSoTimeout(30_000);
            int p = pasv.getLocalPort();
            if (extended) {
                reply(229, "Entering Extended Passive Mode (|||" + p + "|)");
            } else {
                if (!(local instanceof Inet4Address)) {
                    reply(425, "Use EPSV for IPv6");
                    return;
                }
                byte[] a = local.getAddress();
                reply(227, String.format(Locale.US, "Entering Passive Mode (%d,%d,%d,%d,%d,%d)",
                        a[0] & 0xff, a[1] & 0xff, a[2] & 0xff, a[3] & 0xff, p >> 8, p & 0xff));
            }
        }

        private Socket acceptData() throws IOException {
            if (pasv == null) {
                reply(425, "Use PASV first");
                return null;
            }
            try {
                return pasv.accept();
            } catch (IOException e) {
                reply(425, "Data connection failed");
                return null;
            } finally {
                closeQuietly(pasv);
                pasv = null;
            }
        }

        // ---- transfers

        private void list(String arg, boolean namesOnly) throws IOException {
            // Clients often send "LIST -la"; ignore flags.
            String a = arg.trim();
            while (a.startsWith("-")) {
                int sp = a.indexOf(' ');
                a = sp < 0 ? "" : a.substring(sp + 1).trim();
            }
            File dir = resolve(virtualPath(a));
            if (dir == null || !dir.exists()) {
                reply(550, "No such directory");
                return;
            }
            File[] files = dir.isDirectory() ? dir.listFiles() : new File[]{dir};
            if (files == null) files = new File[0];
            Arrays.sort(files, (x, y) -> x.getName().compareToIgnoreCase(y.getName()));

            reply(150, "Here comes the listing");
            Socket data = acceptData();
            if (data == null) return;
            try (Writer w = new OutputStreamWriter(new BufferedOutputStream(data.getOutputStream()), StandardCharsets.UTF_8)) {
                SimpleDateFormat recent = new SimpleDateFormat("MMM dd HH:mm", Locale.US);
                SimpleDateFormat old = new SimpleDateFormat("MMM dd  yyyy", Locale.US);
                long sixMonths = 180L * 24 * 3600 * 1000;
                long now = System.currentTimeMillis();
                for (File f : files) {
                    if (namesOnly) {
                        w.write(f.getName() + "\r\n");
                        continue;
                    }
                    long t = f.lastModified();
                    String date = (Math.abs(now - t) < sixMonths ? recent : old).format(new Date(t));
                    w.write(String.format(Locale.US, "%s 1 tv tv %13d %s %s\r\n",
                            f.isDirectory() ? "drwxr-xr-x" : "-rw-r--r--",
                            f.isDirectory() ? 0 : f.length(), date, f.getName()));
                }
            } finally {
                closeQuietly(data);
            }
            reply(226, "Listing done");
        }

        private void retrieve(String arg) throws IOException {
            File f = resolve(virtualPath(arg));
            long offset = restOffset;
            restOffset = 0;
            if (f == null || !f.isFile()) {
                reply(550, "No such file");
                return;
            }
            reply(150, "Opening data connection");
            Socket data = acceptData();
            if (data == null) return;
            boolean ok = false;
            try (InputStream in = new BufferedInputStream(new FileInputStream(f));
                 OutputStream os = data.getOutputStream()) {
                if (offset > 0) {
                    long skipped = 0;
                    while (skipped < offset) {
                        long s = in.skip(offset - skipped);
                        if (s <= 0) break;
                        skipped += s;
                    }
                }
                copy(in, os);
                ok = true;
            } catch (SocketException e) {
                // client aborted
            } finally {
                closeQuietly(data);
            }
            if (ok) reply(226, "Transfer complete");
            else reply(426, "Transfer aborted");
        }

        private void store(String arg, boolean append) throws IOException {
            File f = resolve(virtualPath(arg));
            long offset = restOffset;
            restOffset = 0;
            if (f == null || f.isDirectory()) {
                reply(553, "Bad file name");
                return;
            }
            File parent = f.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                reply(553, "Can't create folder");
                return;
            }
            reply(150, "Ready to receive");
            Socket data = acceptData();
            if (data == null) return;
            boolean ok = false;
            try (InputStream in = data.getInputStream();
                 OutputStream os = new BufferedOutputStream(new FileOutputStream(f, append || offset > 0))) {
                copy(in, os);
                ok = true;
            } catch (IOException e) {
                ok = false;
            } finally {
                closeQuietly(data);
            }
            if (ok) {
                reply(226, "Upload complete");
                if (listener != null) listener.onUpload(f);
            } else {
                reply(451, "Upload failed (is storage full or read-only?)");
            }
        }

        // ---- paths

        /** Turns a client path (relative or absolute) into a normalised virtual path like "/a/b". */
        private String virtualPath(String arg) {
            String a = arg == null ? "" : arg.trim();
            String combined = a.startsWith("/") ? a : (cwd.endsWith("/") ? cwd : cwd + "/") + a;
            java.util.ArrayDeque<String> parts = new java.util.ArrayDeque<>();
            for (String p : combined.split("/")) {
                if (p.isEmpty() || p.equals(".")) continue;
                if (p.equals("..")) {
                    if (!parts.isEmpty()) parts.removeLast();
                } else {
                    parts.addLast(p);
                }
            }
            return "/" + String.join("/", parts);
        }

        /** Maps a virtual path to a real file, refusing anything outside the root. */
        private File resolve(String virtual) {
            try {
                File base = canonicalRoot();
                File f = new File(base, virtual.substring(1)).getCanonicalFile();
                String b = base.getPath();
                String p = f.getPath();
                if (p.equals(b) || p.startsWith(b.endsWith(File.separator) ? b : b + File.separator)) return f;
            } catch (IOException ignored) {
            }
            return null;
        }
    }

    private File canonicalRoot() throws IOException {
        return root.getCanonicalFile();
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.flush();
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }
}
