package net.runelite.client.plugins.ipcinput;

import java.awt.Component;
import java.awt.Point;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@Slf4j
@PluginDescriptor(
        name = "IPC Input",
        description = "Accepts localhost JSON commands and injects input. Commands: ping, port, where, focus, click, key, type, tilexy, tilexy_many, path, clear_path, mask, info.",
        tags = {"ipc", "automation", "input"},
        enabledByDefault = false
)
public class IpcInputPlugin extends Plugin
{
    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private IpcInputConfig config;

    @Inject private OverlayManager overlayManager;
    private CollisionOverlay collisionOverlay;
    private PathOverlay pathOverlay;

    private ServerThread serverThread;

    @Provides
    IpcInputConfig provideConfig(ConfigManager mgr)
    {
        return mgr.getConfig(IpcInputConfig.class);
    }

    @Override
    protected void startUp()
    {
        final int port = config.port();

        // Visual overlays
        collisionOverlay = new CollisionOverlay(client);
        pathOverlay = new PathOverlay(client);
        overlayManager.add(collisionOverlay);
        overlayManager.add(pathOverlay);

        try
        {
            // Start the IPC server thread
            serverThread = new ServerThread(client, clientThread, config, port, pathOverlay);
            serverThread.start();

            log.info("IPC Input listening on {}", port);
            clientThread.invokeLater(() ->
                    client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "IPC Input listening on " + port, null));
        }
        catch (RuntimeException ex)
        {
            log.error("IPC Input failed to bind port {}: {}", port, ex.getMessage());
            clientThread.invokeLater(() ->
                    client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                            "IPC Input: failed to bind port " + port + " (" + ex.getMessage() + ")", null));
            throw ex;
        }
    }

    @Override
    protected void shutDown()
    {
        if (serverThread != null)
        {
            serverThread.requestStop();
            serverThread = null;
        }

        if (pathOverlay != null)
        {
            overlayManager.remove(pathOverlay);
            pathOverlay = null;
        }
        if (collisionOverlay != null)
        {
            overlayManager.remove(collisionOverlay);
            collisionOverlay = null;
        }

        log.info("IPC Input stopped");
    }

    /* ======================= Server Thread ======================= */

    private static final class ServerThread extends Thread
    {
        private final Client client;
        private final ClientThread clientThread;
        private final IpcInputConfig config;
        private final int port;
        private final PathOverlay pathOverlay;

        private final Gson gson = new Gson();
        private volatile boolean running = true;
        private ServerSocket server;

        ServerThread(Client client, ClientThread clientThread, IpcInputConfig config, int port, PathOverlay pathOverlay)
        {
            super("IPC-Input-Server");
            setDaemon(true);
            this.client = client;
            this.clientThread = clientThread;
            this.config = config;
            this.port = port;
            this.pathOverlay = pathOverlay;

            try
            {
                this.server = new ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"));
                log.info("IPC Input bound 127.0.0.1:{}", port);
            }
            catch (Exception e)
            {
                log.error("IPC Input bind failed on {}: {}", port, e.toString());
                throw new RuntimeException("bind-failed", e);
            }
        }

        void requestStop()
        {
            running = false;
            try { if (server != null) server.close(); } catch (Exception ignored) {}
        }

        @Override
        public void run()
        {
            try (ServerSocket ss = server)
            {
                while (running)
                {
                    try (Socket s = ss.accept();
                         BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
                         PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), "UTF-8"), true))
                    {
                        final String line = in.readLine();
                        if (line == null)
                        {
                            out.println("{\"ok\":false,\"err\":\"empty\"}");
                            continue;
                        }

                        Cmd cmd;
                        try
                        {
                            cmd = gson.fromJson(line.trim(), Cmd.class);
                        }
                        catch (Exception e)
                        {
                            out.println("{\"ok\":false,\"err\":\"bad-json\"}");
                            continue;
                        }
                        if (cmd == null || cmd.cmd == null)
                        {
                            out.println("{\"ok\":false,\"err\":\"bad-request\"}");
                            continue;
                        }

                        switch (cmd.cmd)
                        {
                            case "ping":
                                out.println("{\"ok\":true,\"pong\":true}");
                                break;

                            case "port":
                                out.println("{\"ok\":true,\"port\":" + port + "}");
                                break;

                            case "where": {
                                final Component c = (Component) client.getCanvas();
                                if (c == null)
                                {
                                    out.println("{\"ok\":false,\"err\":\"no-canvas\"}");
                                    break;
                                }
                                try
                                {
                                    final Point p = c.getLocationOnScreen();
                                    out.println("{\"ok\":true,\"x\":" + p.x + ",\"y\":" + p.y +
                                            ",\"w\":" + c.getWidth() + ",\"h\":" + c.getHeight() + "}");
                                }
                                catch (Exception e)
                                {
                                    out.println("{\"ok\":false,\"err\":\"where-failed\"}");
                                }
                                break;
                            }

                            case "focus":
                                clientThread.invokeLater(() -> {
                                    try {
                                        final Component comp = (Component) client.getCanvas();
                                        if (comp != null) comp.requestFocus();
                                    } catch (Exception ignored) {}
                                });
                                out.println("{\"ok\":true}");
                                break;

                            case "click": {
                                if (cmd.x == null || cmd.y == null) { out.println("{\"ok\":false,\"err\":\"need x,y\"}"); break; }
                                final int cx = cmd.x, cy = cmd.y;
                                final int btn = (cmd.button == null) ? 1 : cmd.button;
                                final String mode = config.mode().trim().toUpperCase();

                                if ("ROBOT".equals(mode)) {
                                    // Foreground OS mouse
                                    clientThread.invokeLater(() -> {
                                        try {
                                            final java.awt.Component comp = (java.awt.Component) client.getCanvas();
                                            if (comp == null) return;
                                            final java.awt.Point origin = comp.getLocationOnScreen();
                                            final int sx = origin.x + cx;
                                            final int sy = origin.y + cy;
                                            final java.awt.Robot r = new java.awt.Robot();
                                            final int mask = (btn == 3) ? java.awt.event.InputEvent.BUTTON3_DOWN_MASK :
                                                    (btn == 2) ? java.awt.event.InputEvent.BUTTON2_DOWN_MASK :
                                                            java.awt.event.InputEvent.BUTTON1_DOWN_MASK;
                                            r.mouseMove(sx, sy);
                                            r.mousePress(mask);
                                            r.mouseRelease(mask);
                                        } catch (Exception ignored) {}
                                    });
                                    out.println("{\"ok\":true,\"mode\":\"ROBOT\"}");
                                    break;
                                }

                                // AWT dispatch (background-capable while window is showing)
                                final int hoverDelay = Math.max(0, config.hoverDelayMs());
                                javax.swing.SwingUtilities.invokeLater(() -> {
                                    try {
                                        final java.awt.Component c = (java.awt.Component) client.getCanvas();
                                        if (c == null) {
                                            out.println("{\"ok\":false,\"err\":\"no-canvas\"}");
                                            return;
                                        }

                                        // 1) Hover
                                        final long now = System.currentTimeMillis();
                                        java.awt.event.MouseEvent move =
                                                new java.awt.event.MouseEvent(c, java.awt.event.MouseEvent.MOUSE_MOVED,
                                                        now, 0, cx, cy, 0, false);
                                        c.dispatchEvent(move);
                                        c.requestFocusInWindow();

                                        // 2) Click after delay
                                        final int awtButton = (btn == 3) ? java.awt.event.MouseEvent.BUTTON3
                                                : (btn == 2) ? java.awt.event.MouseEvent.BUTTON2
                                                : java.awt.event.MouseEvent.MOUSE_PRESSED;

                                        javax.swing.Timer t = new javax.swing.Timer(hoverDelay, ev -> {
                                            try {
                                                long t0 = System.currentTimeMillis();
                                                java.awt.event.MouseEvent press =
                                                        new java.awt.event.MouseEvent(c, java.awt.event.MouseEvent.MOUSE_PRESSED,
                                                                t0, 0, cx, cy, 1, false, awtButton);
                                                java.awt.event.MouseEvent release =
                                                        new java.awt.event.MouseEvent(c, java.awt.event.MouseEvent.MOUSE_RELEASED,
                                                                t0 + 1, 0, cx, cy, 1, false, awtButton);
                                                java.awt.event.MouseEvent click =
                                                        new java.awt.event.MouseEvent(c, java.awt.event.MouseEvent.MOUSE_CLICKED,
                                                                t0 + 2, 0, cx, cy, 1, false, awtButton);

                                                c.dispatchEvent(press);
                                                c.dispatchEvent(release);
                                                c.dispatchEvent(click);
                                                c.requestFocusInWindow();
                                            } catch (Exception e) {
                                                log.warn("AWT click dispatch failed: {}", e.toString());
                                            }
                                        });
                                        t.setRepeats(false);
                                        t.start();

                                        out.println("{\"ok\":true,\"mode\":\"AWT\",\"hoverDelayMs\":" + hoverDelay + "}");
                                    } catch (Exception e) {
                                        log.warn("AWT move+click scheduling failed: {}", e.toString());
                                        out.println("{\"ok\":false,\"err\":\"awt-schedule-failed\"}");
                                    }
                                });
                                break;
                            }

                            case "key": {
                                if (cmd.k == null || cmd.k.isEmpty()) { out.println("{\"ok\":false,\"err\":\"need k\"}"); break; }
                                final String mode = config.mode().trim().toUpperCase();
                                final String k = cmd.k;

                                if ("ROBOT".equals(mode)) {
                                    clientThread.invokeLater(() -> {
                                        try {
                                            final java.awt.Robot r = new java.awt.Robot();
                                            int code = keyCodeFrom(k);
                                            r.keyPress(code);
                                            r.keyRelease(code);
                                        } catch (Exception ignored) {}
                                    });
                                    out.println("{\"ok\":true,\"mode\":\"ROBOT\"}");
                                    break;
                                }

                                // AWT key events to canvas
                                javax.swing.SwingUtilities.invokeLater(() -> {
                                    try {
                                        final java.awt.Component c = (java.awt.Component) client.getCanvas();
                                        if (c == null) return;

                                        c.requestFocusInWindow();

                                        final long now = System.currentTimeMillis();
                                        final int code = keyCodeFrom(k);

                                        char keyChar = java.awt.event.KeyEvent.CHAR_UNDEFINED;
                                        if (k.length() == 1) {
                                            char ch = k.charAt(0);
                                            if (Character.isLetterOrDigit(ch) || ch == ' ') keyChar = ch;
                                        }

                                        java.awt.event.KeyEvent kp = new java.awt.event.KeyEvent(
                                                c, java.awt.event.KeyEvent.KEY_PRESSED, now, 0, code,
                                                java.awt.event.KeyEvent.CHAR_UNDEFINED
                                        );
                                        c.dispatchEvent(kp);

                                        if (keyChar != java.awt.event.KeyEvent.CHAR_UNDEFINED) {
                                            java.awt.event.KeyEvent kt = new java.awt.event.KeyEvent(
                                                    c, java.awt.event.KeyEvent.KEY_TYPED, now + 1, 0,
                                                    java.awt.event.KeyEvent.VK_UNDEFINED, keyChar
                                            );
                                            c.dispatchEvent(kt);
                                        }

                                        try { Thread.sleep(10L); } catch (InterruptedException ignored) {}

                                        java.awt.event.KeyEvent kr = new java.awt.event.KeyEvent(
                                                c, java.awt.event.KeyEvent.KEY_RELEASED, now + 2, 0, code,
                                                java.awt.event.KeyEvent.CHAR_UNDEFINED
                                        );
                                        c.dispatchEvent(kr);

                                    } catch (Exception e) {
                                        log.warn("AWT key dispatch failed: {}", e.toString());
                                    }
                                });
                                out.println("{\"ok\":true,\"mode\":\"AWT\"}");
                                break;
                            }

                            case "type": {
                                final String text = (cmd.text == null) ? "" : cmd.text;
                                final boolean pressEnter = cmd.enter != null && cmd.enter;
                                final int perCharMs = (cmd.perCharMs == null) ? 25 : Math.max(0, cmd.perCharMs);
                                final boolean focus = cmd.focus == null || cmd.focus;
                                final String mode = config.mode().trim().toUpperCase();

                                if (text.isEmpty()) { out.println("{\"ok\":false,\"err\":\"need text\"}"); break; }

                                out.println("{\"ok\":true,\"mode\":\"" + mode + "\",\"accepted\":\"type\"}");

                                new Thread(() -> {
                                    try {
                                        if ("ROBOT".equals(mode)) {
                                            robotTypeString(text, pressEnter, perCharMs);
                                        } else {
                                            typeStringAWT(client, text, pressEnter, perCharMs, focus);
                                        }
                                    } catch (Throwable th) {
                                        log.warn("type command failed: {}", th.toString());
                                    }
                                }, "ipc-type").start();
                                break;
                            }

                            case "tilexy": {
                                if (cmd.x == null || cmd.y == null) {
                                    out.println("{\"ok\":false,\"err\":\"need x,y\"}");
                                    break;
                                }
                                Map<String,Object> r = worldTileToCanvas(cmd.x, cmd.y);
                                out.println(gson.toJson(r));
                                break;
                            }

                            case "tilexy_many": {
                                List<Map<String,Integer>> tiles = cmd.tiles;
                                if (tiles == null) tiles = java.util.Collections.emptyList();
                                Map<String,Object> r = projectMany(tiles);
                                out.println(gson.toJson(r));
                                break;
                            }

                            case "mask": {
                                int r = (cmd.radius == null) ? 15 : Math.max(1, Math.min(30, cmd.radius));
                                Map<String,Object> m = buildMask(r);
                                out.println(gson.toJson(m));
                                break;
                            }

                            case "clear_path": {
                                clientThread.invokeLater(() -> pathOverlay.clearPath());
                                out.println("{\"ok\":true}");
                                break;
                            }

                            // {"cmd":"path","goalX":3095,"goalY":3493,"maxWps":10}
                            case "path": {
                                int gx = cmd.goalX, gy = cmd.goalY;
                                int maxWps = cmd.maxWps != null ? Math.max(1, cmd.maxWps) : 10;

                                // compute full path
                                List<WorldPoint> fullPath = computePathTo(gx, gy);

                                // push full path to overlay (on client thread for safety)
                                WorldPoint startWp = null;
                                try { if (client.getLocalPlayer() != null) startWp = client.getLocalPlayer().getWorldLocation(); }
                                catch (Exception ignored) {}
                                WorldPoint goalWp = new WorldPoint(gx, gy, client.getPlane());
                                final WorldPoint fStart = startWp;
                                final WorldPoint fGoal  = goalWp;
                                clientThread.invokeLater(() -> pathOverlay.setPath(fullPath, fStart, fGoal));

                                // subsample for response (click pacing)
                                List<Map<String,Integer>> wpsOut = new ArrayList<>();
                                int step = (fullPath.size() <= maxWps) ? 1 : Math.max(1, fullPath.size() / maxWps);
                                for (int i = 0; i < fullPath.size(); i += step)
                                {
                                    WorldPoint w = fullPath.get(i);
                                    Map<String,Integer> m = new LinkedHashMap<>();
                                    m.put("x", w.getX()); m.put("y", w.getY()); m.put("p", w.getPlane());
                                    wpsOut.add(m);
                                }
                                if (!fullPath.isEmpty()) {
                                    WorldPoint last = fullPath.get(fullPath.size()-1);
                                    Map<String,Integer> m = new LinkedHashMap<>();
                                    m.put("x", last.getX()); m.put("y", last.getY()); m.put("p", last.getPlane());
                                    if (wpsOut.isEmpty() || !wpsOut.get(wpsOut.size()-1).equals(m)) {
                                        wpsOut.add(m);
                                    }
                                }

                                Map<String,Object> resp = new LinkedHashMap<>();
                                resp.put("ok", true);
                                resp.put("waypoints", wpsOut);
                                resp.put("pathLen", fullPath.size());
                                out.println(gson.toJson(resp));
                                break;
                            }

                            case "info": {
                                String playerName = "";
                                try {
                                    if (client != null && client.getLocalPlayer() != null) {
                                        playerName = client.getLocalPlayer().getName();
                                    }
                                } catch (Exception ignored) {}

                                Map<String, Object> resp = new LinkedHashMap<>();
                                resp.put("ok", true);
                                resp.put("player", playerName);
                                out.println(gson.toJson(resp));
                                break;
                            }

                            default:
                                out.println("{\"ok\":false,\"err\":\"unknown-cmd\"}");
                        }
                    }
                    catch (Exception perConn)
                    {
                        // keep server alive for next connection
                    }
                }
            }
            catch (Exception fatal)
            {
                // exiting
            }
        }

        /* ======================= Core helpers ======================= */

        private Map<String,Object> worldTileToCanvas(int wx, int wy)
        {
            Map<String,Object> resp = new LinkedHashMap<>();
            int plane = client.getPlane();
            LocalPoint lp = LocalPoint.fromWorld(client, wx, wy);
            if (lp == null) {
                resp.put("ok", false);
                resp.put("err", "off-scene");
                return resp;
            }

            net.runelite.api.Point p = Perspective.localToCanvas(client, lp, plane);
            if (p == null) {
                resp.put("ok", true);
                resp.put("onscreen", false);
                return resp;
            }

            resp.put("ok", true);
            resp.put("onscreen", true);
            Map<String,Integer> c = new LinkedHashMap<>();
            c.put("x", p.getX());
            c.put("y", p.getY());
            resp.put("canvas", c);
            return resp;
        }

        private Map<String,Object> projectMany(List<Map<String,Integer>> tiles)
        {
            Map<String,Object> resp = new LinkedHashMap<>();
            List<Map<String,Object>> out = new ArrayList<>();
            int plane = client.getPlane();
            for (Map<String,Integer> t : tiles) {
                Integer wx = t.get("x"), wy = t.get("y");
                if (wx == null || wy == null) {
                    out.add(java.util.Map.of("ok", false, "err", "bad-tile"));
                    continue;
                }
                LocalPoint lp = LocalPoint.fromWorld(client, wx, wy);
                if (lp == null) { out.add(java.util.Map.of("ok", true, "onscreen", false)); continue; }
                net.runelite.api.Point p = Perspective.localToCanvas(client, lp, plane);
                if (p == null) { out.add(java.util.Map.of("ok", true, "onscreen", false)); continue; }
                out.add(java.util.Map.of("ok", true, "onscreen", true,
                        "canvas", java.util.Map.of("x", p.getX(), "y", p.getY())));
            }
            resp.put("ok", true);
            resp.put("results", out);
            return resp;
        }

        private Map<String,Object> buildMask(int radius)
        {
            Map<String,Object> resp = new LinkedHashMap<>();
            var lp = client.getLocalPlayer();
            if (lp == null) { resp.put("ok", false); resp.put("err", "no-player"); return resp; }

            int plane = client.getPlane();
            var cms = client.getCollisionMaps();
            if (cms == null || plane < 0 || plane >= cms.length || cms[plane] == null) {
                resp.put("ok", false); resp.put("err", "no-cm"); return resp;
            }
            int[][] flags = cms[plane].getFlags();

            final int baseX = client.getBaseX();
            final int baseY = client.getBaseY();
            int wx = lp.getWorldLocation().getX();
            int wy = lp.getWorldLocation().getY();

            List<String> rows = new ArrayList<>();
            for (int gy = wy + radius; gy >= wy - radius; gy--) {
                StringBuilder sb = new StringBuilder(radius*2+1);
                for (int gx = wx - radius; gx <= wx + radius; gx++) {
                    int lx = gx - baseX, ly = gy - baseY;
                    char ch = '#';
                    if (lx >= 0 && ly >= 0 && lx < 104 && ly < 104) {
                        int f = flags[lx][ly];
                        boolean solid  = (f & 0x200000) != 0; // SOLID
                        boolean object = (f & 0x000100) != 0; // OBJECT
                        ch = (solid || object) ? '#' : '.';
                    }
                    sb.append(ch);
                }
                rows.add(sb.toString());
            }

            resp.put("ok", true);
            resp.put("plane", plane);
            resp.put("baseX", baseX);
            resp.put("baseY", baseY);
            resp.put("origin", java.util.Map.of("x", wx, "y", wy));
            resp.put("radius", radius);
            resp.put("rows", rows); // top row north, left col west
            return resp;
        }

        private List<WorldPoint> computePathTo(int goalWx, int goalWy)
        {
            var lp = client.getLocalPlayer();
            if (lp == null) return java.util.Collections.emptyList();
            int plane = client.getPlane();

            var cms = client.getCollisionMaps();
            if (cms == null || plane < 0 || plane >= cms.length || cms[plane] == null)
                return java.util.Collections.emptyList();
            int[][] flags = cms[plane].getFlags();

            final int baseX = client.getBaseX();
            final int baseY = client.getBaseY();

            int startWx = lp.getWorldLocation().getX();
            int startWy = lp.getWorldLocation().getY();
            int sx = startWx - baseX;
            int sy = startWy - baseY;

            int gx = goalWx - baseX;
            int gy = goalWy - baseY;

            if (!inScene(sx, sy) || !inScene(gx, gy)) return java.util.Collections.emptyList();
            if (!isWalkableLocal(flags, sx, sy) || !isWalkableLocal(flags, gx, gy))
                return java.util.Collections.emptyList();

            final int W = 104, H = 104;
            int[][] prev = new int[W][H]; // pack prev as (px<<16)|py
            boolean[][] seen = new boolean[W][H];
            Deque<int[]> dq = new ArrayDeque<>();
            dq.add(new int[]{sx, sy});
            seen[sx][sy] = true;

            // 4-neighbors
            int[] dirs = { 1,0, -1,0, 0,1, 0,-1 };
            boolean found = false;
            int curx = sx, cury = sy;

            int expansions = 0, MAX_EXPANSIONS = 12000;
            while (!dq.isEmpty() && expansions++ < MAX_EXPANSIONS) {
                int[] n = dq.removeFirst();
                curx = n[0]; cury = n[1];
                if (curx == gx && cury == gy) { found = true; break; }

                for (int i = 0; i < 8; i += 2) {
                    int nx = curx + dirs[i], ny = cury + dirs[i+1];
                    if (!inScene(nx, ny) || seen[nx][ny]) continue;
                    if (!isWalkableLocal(flags, nx, ny)) continue;

                    seen[nx][ny] = true;
                    prev[nx][ny] = (curx << 16) | (cury & 0xFFFF);
                    dq.addLast(new int[]{nx, ny});
                }
            }
            if (!found) return java.util.Collections.emptyList();

            ArrayList<WorldPoint> path = new ArrayList<>();
            int px = gx, py = gy;
            while (!(px == sx && py == sy)) {
                path.add(new WorldPoint(px + baseX, py + baseY, plane));
                int packed = prev[px][py];
                int ppx = (packed >>> 16);
                int ppy = (packed & 0xFFFF);
                px = ppx; py = ppy;
            }
            java.util.Collections.reverse(path);
            return path;
        }

        private static boolean inScene(int lx, int ly) {
            return lx >= 0 && ly >= 0 && lx < 104 && ly < 104;
        }

        private static boolean isWalkableLocal(int[][] flags, int lx, int ly) {
            int f = flags[lx][ly];
            boolean solid  = (f & 0x200000) != 0; // SOLID tile
            boolean object = (f & 0x000100) != 0; // OBJECT present
            return !(solid || object);
        }

        private static int keyCodeFrom(String k)
        {
            switch (k.toUpperCase())
            {
                case "ESC":
                case "ESCAPE": return java.awt.event.KeyEvent.VK_ESCAPE;
                case "SPACE":  return java.awt.event.KeyEvent.VK_SPACE;
                case "ENTER":
                case "RETURN": return java.awt.event.KeyEvent.VK_ENTER;
                case "TAB":    return java.awt.event.KeyEvent.VK_TAB;
                default:
                    char ch = k.toUpperCase().charAt(0);
                    return java.awt.event.KeyEvent.getExtendedKeyCodeForChar(ch);
            }
        }

        private static final class Cmd
        {
            // Common
            String cmd;

            // click
            Integer x;
            Integer y;
            Integer button;

            // key
            @SerializedName("k")
            String k;

            // type
            @SerializedName("text")      String text;
            @SerializedName("enter")     Boolean enter;
            @SerializedName("perCharMs") Integer perCharMs;
            @SerializedName("focus")     Boolean focus;

            // path
            @SerializedName("goalX") Integer goalX;
            @SerializedName("goalY") Integer goalY;
            @SerializedName("maxWps") Integer maxWps;

            // mask
            @SerializedName("radius") Integer radius;

            // tilexy_many
            @SerializedName("tiles") List<Map<String,Integer>> tiles;
        }

        private void typeStringAWT(Client client,
                                   String text,
                                   boolean pressEnter,
                                   int perCharMs,
                                   boolean focusCanvas)
        {
            try {
                final java.awt.Component c = (java.awt.Component) client.getCanvas();
                if (c == null) return;

                if (focusCanvas) {
                    java.awt.EventQueue.invokeAndWait(c::requestFocusInWindow);
                    Thread.sleep(10L);
                }

                for (int idx = 0; idx < text.length(); idx++) {
                    final char ch = text.charAt(idx);
                    final int vk = java.awt.event.KeyEvent.getExtendedKeyCodeForChar(ch);

                    java.awt.EventQueue.invokeAndWait(() -> {
                        long t = System.currentTimeMillis();
                        c.dispatchEvent(new java.awt.event.KeyEvent(
                                c, java.awt.event.KeyEvent.KEY_PRESSED, t, 0,
                                vk, java.awt.event.KeyEvent.CHAR_UNDEFINED
                        ));
                        if (Character.isLetterOrDigit(ch) || ch == ' ') {
                            c.dispatchEvent(new java.awt.event.KeyEvent(
                                    c, java.awt.event.KeyEvent.KEY_TYPED, t + 1, 0,
                                    java.awt.event.KeyEvent.VK_UNDEFINED, ch
                            ));
                        }
                    });

                    Thread.sleep(10L);

                    java.awt.EventQueue.invokeAndWait(() -> {
                        long t = System.currentTimeMillis();
                        c.dispatchEvent(new java.awt.event.KeyEvent(
                                c, java.awt.event.KeyEvent.KEY_RELEASED, t, 0,
                                vk, java.awt.event.KeyEvent.CHAR_UNDEFINED
                        ));
                    });

                    if (perCharMs > 0) Thread.sleep(perCharMs);
                }

                if (pressEnter) {
                    final int vkEnter = java.awt.event.KeyEvent.VK_ENTER;
                    java.awt.EventQueue.invokeAndWait(() -> {
                        long t = System.currentTimeMillis();
                        c.dispatchEvent(new java.awt.event.KeyEvent(
                                c, java.awt.event.KeyEvent.KEY_PRESSED, t, 0, vkEnter,
                                java.awt.event.KeyEvent.CHAR_UNDEFINED
                        ));
                        c.dispatchEvent(new java.awt.event.KeyEvent(
                                c, java.awt.event.KeyEvent.KEY_TYPED, t + 1, 0,
                                java.awt.event.KeyEvent.VK_UNDEFINED, '\n'
                        ));
                    });
                    Thread.sleep(10L);
                    java.awt.EventQueue.invokeAndWait(() -> {
                        long t = System.currentTimeMillis();
                        c.dispatchEvent(new java.awt.event.KeyEvent(
                                c, java.awt.event.KeyEvent.KEY_RELEASED, t, 0, vkEnter,
                                java.awt.event.KeyEvent.CHAR_UNDEFINED
                        ));
                    });
                }
            } catch (Throwable th) {
                System.out.println("typeStringAWT error: " + th);
            }
        }

        private void robotTypeString(String text, boolean pressEnter, int perCharMs) throws Exception
        {
            final java.awt.Robot r = new java.awt.Robot();
            for (int i = 0; i < text.length(); i++) {
                final char ch = text.charAt(i);
                final int vk = java.awt.event.KeyEvent.getExtendedKeyCodeForChar(ch);
                r.keyPress(vk);
                r.keyRelease(vk);
                if (perCharMs > 0) try { Thread.sleep(perCharMs); } catch (InterruptedException ignored) {}
            }
            if (pressEnter) {
                r.keyPress(java.awt.event.KeyEvent.VK_ENTER);
                r.keyRelease(java.awt.event.KeyEvent.VK_ENTER);
            }
        }
    }
}
