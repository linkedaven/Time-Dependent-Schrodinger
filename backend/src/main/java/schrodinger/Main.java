package schrodinger;

import java.awt.Desktop;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Starts the web app: <code>java -cp backend/out schrodinger.Main</code>.
 *
 * <pre>
 *   --port N        port to listen on (default 8006)
 *   --frontend DIR  folder with index.html (default: a "frontend" folder found nearby, else the copy packed in the jar)
 *   --no-browser    do not open the browser
 *   --no-watchdog   keep running after the browser tab is closed
 * </pre>
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        int port = Server.DEFAULT_PORT;
        Path frontend = null;
        boolean browser = true, watchdog = true;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--frontend" -> frontend = Paths.get(args[++i]);
                case "--no-browser" -> browser = false;
                case "--no-watchdog" -> watchdog = false;
                case "--help", "-h" -> {
                    System.out.println("Options: --port N  --frontend DIR  --no-browser  --no-watchdog");
                    return;
                }
                default -> {
                    System.err.println("Unknown option: " + args[i]);
                    System.exit(2);
                }
            }
        }
        if (frontend == null) frontend = findFrontend();
        if (frontend == null && Main.class.getResource("/frontend/index.html") == null) {
            fatal("Cannot find the pages (index.html). Rebuild the jar with build.bat or build.sh, or use --frontend DIR.");
        }
        if (frontend != null && !Files.isRegularFile(frontend.resolve("index.html"))) {
            fatal("There is no index.html in " + frontend.toAbsolutePath().normalize());
        }
        String url = "http://127.0.0.1:" + port + "/";
        if (isListening(port)) {
            System.out.println("Already running at " + url);
            if (browser) open(url);
            return;
        }
        Server server = null;
        try {
            server = Server.start(port, frontend);
        } catch (java.io.IOException e) {
            fatal("Cannot start the server on port " + port + ": " + e.getMessage()
                    + "\nAnother program may be using that port. Try --port 9000.");
        }
        System.out.println("Schrödinger Solver running at " + url + "  (Ctrl+C to stop)");
        System.out.println("Serving " + (frontend == null ? "the pages packed inside the jar" : frontend.toAbsolutePath().normalize()));
        if (browser) open(url);
        final Server running = server;
        if (watchdog) {
            Thread t = new Thread(() -> {
                while (true) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (running.shouldQuit(System.currentTimeMillis())) {
                        System.out.println("Browser tab closed, stopping.");
                        System.exit(0);
                    }
                }
            }, "watchdog");
            t.setDaemon(true);
            t.start();
        }
    }

    /** Reports a fatal problem on stderr and, when started from the jar (no console), in a message box. */
    private static void fatal(String message) {
        System.err.println(message);
        try {
            Path code = Paths.get(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (code.toString().endsWith(".jar") && !java.awt.GraphicsEnvironment.isHeadless()) {
                javax.swing.JOptionPane.showMessageDialog(null, message, "Schrödinger Solver",
                        javax.swing.JOptionPane.ERROR_MESSAGE);
            }
        } catch (Exception ignored) {
            // the message is already on stderr
        }
        System.exit(1);
    }

    private static boolean isListening(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void open(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception ignored) {
            // fall through to the message below
        }
        System.out.println("Open " + url + " in your browser.");
    }

    /** Looks for frontend/index.html in the working directory, its parent, and above the compiled classes. */
    private static Path findFrontend() {
        List<Path> starts = new ArrayList<>();
        starts.add(Paths.get("").toAbsolutePath());
        try {
            starts.add(Paths.get(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath());
        } catch (Exception ignored) {
            // no code location (unusual), the working directory is enough
        }
        for (Path start : starts) {
            Path p = Files.isDirectory(start) ? start : start.getParent();
            for (int up = 0; p != null && up < 5; up++, p = p.getParent()) {
                Path candidate = p.resolve("frontend");
                if (Files.isRegularFile(candidate.resolve("index.html"))) return candidate;
            }
        }
        return null;
    }
}
