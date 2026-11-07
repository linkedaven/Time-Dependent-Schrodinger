package schrodinger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The web server: serves the front-end files and one API call that runs a simulation. Built on the JDK's own
 * com.sun.net.httpserver, so there is nothing to download.
 *
 * <pre>
 *   GET /api/simulate?potential=barrier&amp;v0=6&amp;width=1&amp;x0=-15&amp;k0=3&amp;sigma=1&amp;method=split&amp;...
 *       binary reply: int32 length of the JSON, the JSON (padded to a multiple of 4 bytes), then the frames as
 *       float32 (per frame: density, Re psi, Im psi, momentum density)
 * </pre>
 */
public final class Server {
    public static final int DEFAULT_PORT = 8006;

    /** An error that is reported to the browser as {"error": message} with the given HTTP status. */
    static final class ApiException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int status;

        ApiException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private final HttpServer http;
    private final Path frontend;         // null: serve the copy packed inside the jar
    private final AtomicLong lastPing = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong byeAt = new AtomicLong(0);

    private Server(HttpServer http, Path frontend) {
        this.http = http;
        this.frontend = frontend == null ? null : frontend.toAbsolutePath().normalize();
    }

    /**
     * Starts listening on 127.0.0.1. Pass port 0 to let the system choose a free port.
     * With a null frontend folder the pages are served from the copy packed inside the jar.
     */
    public static Server start(int port, Path frontend) throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        Server s = new Server(http, frontend);
        http.createContext("/", ex -> s.guard(ex, s::serveStatic));
        http.createContext("/api/simulate", ex -> s.guard(ex, s::apiSimulate));
        http.createContext("/api/ping", ex -> s.guard(ex, s::apiPing));
        http.createContext("/api/bye", ex -> s.guard(ex, s::apiBye));
        http.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "http");
            t.setDaemon(true);
            return t;
        }));
        http.start();
        return s;
    }

    public int port() {
        return http.getAddress().getPort();
    }

    public void stop() {
        http.stop(0);
    }

    /** True when the page has said goodbye (tab closed) and the grace period is over, or has not pinged for two minutes. */
    public boolean shouldQuit(long now) {
        long bye = byeAt.get();
        return (bye > 0 && now > bye) || now - lastPing.get() > 120_000;
    }

    // ------------------------------------------------------------------ plumbing
    private interface Handler {
        void handle(HttpExchange ex) throws Exception;
    }

    private void guard(HttpExchange ex, Handler h) throws IOException {
        try {
            if (!"GET".equals(ex.getRequestMethod()) && !"POST".equals(ex.getRequestMethod())) {
                throw new ApiException(405, "Only GET and POST are supported.");
            }
            h.handle(ex);
        } catch (ApiException e) {
            sendJson(ex, e.status, Map.of("error", e.getMessage()));
        } catch (Simulation.SimulationException e) {
            sendJson(ex, 400, Map.of("error", e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            sendJson(ex, 500, Map.of("error", "Internal error: " + e.getClass().getSimpleName()));
        } finally {
            ex.close();
        }
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
    }

    private static void sendJson(HttpExchange ex, int status, Object value) throws IOException {
        send(ex, status, "application/json; charset=utf-8", Json.stringify(value).getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> q = new LinkedHashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isEmpty()) return q;
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            String k = eq < 0 ? part : part.substring(0, eq), v = eq < 0 ? "" : part.substring(eq + 1);
            q.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return q;
    }

    private static double num(Map<String, String> q, String name, double def, double min, double max) {
        String raw = q.get(name);
        if (raw == null || raw.isEmpty()) return def;
        double v;
        try {
            v = Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            throw new ApiException(400, name + " must be a number.");
        }
        if (!Double.isFinite(v)) throw new ApiException(400, name + " must be a finite number.");
        if (v < min || v > max) throw new ApiException(400, name + " must be between " + min + " and " + max + ".");
        return v;
    }

    // ------------------------------------------------------------------ static files
    private static String mime(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".html")) return "text/html; charset=utf-8";
        if (n.endsWith(".css")) return "text/css; charset=utf-8";
        if (n.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (n.endsWith(".json")) return "application/json; charset=utf-8";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    private void serveStatic(HttpExchange ex) throws Exception {
        String path = URLDecoder.decode(ex.getRequestURI().getRawPath(), StandardCharsets.UTF_8);
        if (path.equals("/")) path = "/index.html";
        if (path.contains("\u0000") || path.contains("\\")) throw new ApiException(400, "Bad path.");
        if (frontend == null) {
            String last = path.substring(path.lastIndexOf('/') + 1);
            if (path.contains("..") || path.contains("//") || !last.contains(".")) throw new ApiException(404, "Not found: " + path);
            try (InputStream in = Server.class.getResourceAsStream("/frontend" + path)) {
                if (in == null) throw new ApiException(404, "Not found: " + path);
                send(ex, 200, mime(last), in.readAllBytes());
            }
            return;
        }
        Path file = frontend.resolve(path.substring(1)).normalize();
        if (!file.startsWith(frontend) || !Files.isRegularFile(file)) throw new ApiException(404, "Not found: " + path);
        send(ex, 200, mime(file.getFileName().toString()), Files.readAllBytes(file));
    }

    // ------------------------------------------------------------------ watchdog endpoints
    private void apiPing(HttpExchange ex) throws IOException {
        lastPing.set(System.currentTimeMillis());
        byeAt.set(0);
        sendJson(ex, 200, Map.of("ok", true));
    }

    private void apiBye(HttpExchange ex) throws IOException {
        byeAt.set(System.currentTimeMillis() + 5000);
        sendJson(ex, 200, Map.of("ok", true));
    }

    // ------------------------------------------------------------------ /api/simulate
    static Simulation.Params parse(Map<String, String> q) {
        String id = q.getOrDefault("potential", "barrier");
        Potential.Kind kind = Potential.Kind.of(id);
        if (kind == null) {
            throw new ApiException(400, "Unknown potential \"" + id + "\". Use free, step, barrier, well, double_barrier, harmonic or double_well.");
        }
        String mid = q.getOrDefault("method", "split");
        Simulation.Method method = Simulation.Method.of(mid);
        if (method == null) throw new ApiException(400, "Unknown method \"" + mid + "\". Use split, cn or euler.");
        double v0 = num(q, "v0", 6.0, 0.0, 100.0);
        double width = num(q, "width", 1.0, 0.05, 30.0);
        double gap = num(q, "gap", 1.5, 0.05, 40.0);
        double omega = num(q, "omega", 0.5, 0.05, 5.0);
        double x0 = num(q, "x0", -15.0, -25.0, 25.0);
        double k0 = num(q, "k0", 3.0, -8.0, 8.0);
        double sigma = num(q, "sigma", 1.0, 0.2, 8.0);
        int n = (int) num(q, "n", 2048, 512, 4096);
        if (n != 512 && n != 1024 && n != 2048 && n != 4096) throw new ApiException(400, "n must be 512, 1024, 2048 or 4096.");
        double dt = num(q, "dt", 0.002, 0.0005, 0.05);
        double tmax = num(q, "tmax", 14.0, 0.5, 80.0);
        String edges = q.getOrDefault("edges", "absorbing");
        if (!edges.equals("absorbing") && !edges.equals("walls")) throw new ApiException(400, "edges must be absorbing or walls.");
        return new Simulation.Params(new Potential(kind, v0, width, gap, omega), x0, k0, sigma, method, n, dt, tmax,
                edges.equals("absorbing"));
    }

    private void apiSimulate(HttpExchange ex) throws Exception {
        Simulation.Params p = parse(query(ex.getRequestURI()));
        Simulation.Result r = Simulation.run(p);
        byte[] json = Json.stringify(r.meta).getBytes(StandardCharsets.UTF_8);
        int padded = (json.length + 3) & ~3;
        ByteBuffer buf = ByteBuffer.allocate(4 + padded + 4 * r.data.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(padded);
        buf.put(json);
        for (int i = json.length; i < padded; i++) buf.put((byte) ' ');
        buf.asFloatBuffer().put(r.data);
        send(ex, 200, "application/octet-stream", buf.array());
    }
}
