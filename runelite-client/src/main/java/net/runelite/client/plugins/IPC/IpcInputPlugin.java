package net.runelite.client.plugins.ipcinput;

import java.awt.*;
import java.awt.Point;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
//import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

/**
 * IPC Input + Path overlay with verbose debug.
 * Commands: ping, port, where, click, key, type, tilexy, tilexy_many, mask, clear_path, path, path_edge, path_rect
 */
@Slf4j
@PluginDescriptor(
        name = "IPC Input",
        description = "Accepts localhost JSON commands and injects input. Also computes & overlays scene paths using collision flags.",
        tags = {"ipc", "automation", "input", "path"},
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

    // Edgeville Bank bounds
    private static final int EDGE_BANK_MIN_X = 3092;
    private static final int EDGE_BANK_MAX_X = 3098;
    private static final int EDGE_BANK_MIN_Y = 3488;
    private static final int EDGE_BANK_MAX_Y = 3498;

    // GE bounds
    private static final int GE_MIN_X = 3155;
    private static final int GE_MAX_X = 3173;
    private static final int GE_MIN_Y = 3479;
    private static final int GE_MAX_Y = 3498;

    @Provides
    IpcInputConfig provideConfig(ConfigManager mgr) { return mgr.getConfig(IpcInputConfig.class); }

    @Override
    protected void startUp()
    {
        final int port = config.port();

        collisionOverlay = new CollisionOverlay(client);
        pathOverlay = new PathOverlay(client);
        overlayManager.add(collisionOverlay);
        overlayManager.add(pathOverlay);

        try {
            serverThread = new ServerThread(client, clientThread, config, port, pathOverlay);
            serverThread.start();
            log.info("IPC Input listening on {}", port);
            clientThread.invokeLater(() ->
                    client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "IPC Input listening on " + port, null)
            );
        } catch (RuntimeException ex) {
            log.error("IPC Input failed to bind port {}: {}", port, ex.getMessage());
            clientThread.invokeLater(() ->
                    client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                            "IPC Input: failed to bind port " + port + " (" + ex.getMessage() + ")", null)
            );
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

        // --- BEGIN DEBUG STRUCTS ---
        // Put this inside IpcInputPlugin.ServerThread (replace your existing PathDebug)
        private static final class PathDebug
        {
            // Inputs / scene info
            int plane;
            int baseX, baseY;

            // Start & goal (world)
            int startWx, startWy;
            int goalWx,  goalWy;

            // Start & goal (local)
            int startLx, startLy;
            int goalLx,  goalLy;

            // Validity flags
            boolean startInScene;
            boolean goalInScene;
            boolean startWalkable;

            // BFS bookkeeping
            int maxExpansions;
            int expansions;
            boolean foundGoal;    // reached true goal tile
            boolean returnedBest; // returned "best toward goal" fallback

            // Chosen end / best info
            int bestLx, bestLy;
            int bestWx, bestWy;
            int bestManhattan;

            // Text dumps (for logs)
            java.util.List<String> start9x9;
            java.util.List<String> goal9x9;
            java.util.List<String> best9x9;

            // Misc
            String whyFailed;
            long timeMs;

            // Extra counters (skippedBlocked, skippedCorner, enqueued, seenCount, …)
            java.util.Map<String, Integer> extraCounters = new java.util.LinkedHashMap<>(); // <-- and this
        }

        // Put inside IpcInputPlugin.ServerThread
        private static final class PathResult {
            final java.util.List<WorldPoint> path;
            final PathDebug debug; // <— this is what you read as pr.debug

            PathResult(java.util.List<WorldPoint> path, PathDebug debug) {
                this.path = path != null ? path : java.util.Collections.emptyList();
                this.debug = debug;
            }
        }
        // --- END DEBUG STRUCTS ---

        private static final class Rect {
            final int minX, maxX, minY, maxY;
            Rect(int minX, int maxX, int minY, int maxY) {
                this.minX = minX; this.maxX = maxX; this.minY = minY; this.maxY = maxY;
            }
            WorldPoint center(int plane) {
                int cx = (minX + maxX) / 2;
                int cy = (minY + maxY) / 2;
                return new WorldPoint(cx, cy, plane);
            }
            boolean containsWorld(int wx, int wy) {
                return wx >= minX && wx <= maxX && wy >= minY && wy <= maxY;
            }
        }

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
                log.info("[IPC] bind 127.0.0.1:{}", port);
            }
            catch (Exception e)
            {
                log.error("[IPC] bind failed on {}: {}", port, e.toString());
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

//                        log.info("[IPC] recv: {}", line);
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
//                        log.info("[IPC] cmd={}", cmd.cmd);

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

                            case "click": {
                                if (cmd.x == null || cmd.y == null) { out.println("{\"ok\":false,\"err\":\"need x,y\"}"); break; }
                                final int cx = cmd.x, cy = cmd.y;
                                final int btn = (cmd.button == null) ? 1 : cmd.button;
                                final String mode = config.mode().trim().toUpperCase();

                                if ("ROBOT".equals(mode)) {
                                    clientThread.invokeLater(() -> {
                                        try {
                                            final java.awt.Component comp = (java.awt.Component) client.getCanvas();
                                            if (comp == null) return;
                                            final java.awt.Point origin = comp.getLocationOnScreen();
                                            final int sx = origin.x + cx;
                                            final int sy = origin.y + cy;
                                            final java.awt.Robot r = new java.awt.Robot();
                                            final int mask = (btn == 3) ? java.awt.event.InputEvent.BUTTON3_DOWN_MASK
                                                    : (btn == 2) ? java.awt.event.InputEvent.BUTTON2_DOWN_MASK
                                                    : java.awt.event.InputEvent.BUTTON1_DOWN_MASK;
                                            r.mouseMove(sx, sy);
                                            r.mousePress(mask);
                                            r.mouseRelease(mask);
                                        } catch (Exception ignored) {}
                                    });
                                    out.println("{\"ok\":true,\"mode\":\"ROBOT\"}");
                                    break;
                                }

                                // AWT dispatch (window showing / background-capable)
                                final int hoverDelay = Math.max(0, config.hoverDelayMs());

                                // >>> RESPOND BEFORE scheduling async UI work <<<
                                out.println("{\"ok\":true,\"mode\":\"AWT\",\"hoverDelayMs\":" + hoverDelay + "}");

                                javax.swing.SwingUtilities.invokeLater(() -> {
                                    try {
                                        final java.awt.Component c = (java.awt.Component) client.getCanvas();
                                        if (c == null) return;

                                        // 1) Hover
                                        long now = System.currentTimeMillis();
                                        c.dispatchEvent(new java.awt.event.MouseEvent(
                                                c, java.awt.event.MouseEvent.MOUSE_MOVED, now, 0, cx, cy, 0, false));
                                        c.requestFocusInWindow();

                                        // 2) Click after delay
                                        final int awtButton = (btn == 3) ? java.awt.event.MouseEvent.BUTTON3
                                                : (btn == 2) ? java.awt.event.MouseEvent.BUTTON2
                                                : java.awt.event.MouseEvent.BUTTON1;

                                        javax.swing.Timer t = new javax.swing.Timer(hoverDelay, ev -> {
                                            final boolean wasFocusable = c.isFocusable();
                                            try {
                                                c.setFocusable(false);
                                                long t0 = System.currentTimeMillis();
                                                c.dispatchEvent(new java.awt.event.MouseEvent(
                                                        c, java.awt.event.MouseEvent.MOUSE_PRESSED,  t0,   0, cx, cy, 1, false, awtButton));
                                                c.dispatchEvent(new java.awt.event.MouseEvent(
                                                        c, java.awt.event.MouseEvent.MOUSE_RELEASED, t0+1, 0, cx, cy, 1, false, awtButton));
                                                c.dispatchEvent(new java.awt.event.MouseEvent(
                                                        c, java.awt.event.MouseEvent.MOUSE_CLICKED,  t0+2, 0, cx, cy, 1, false, awtButton));
                                            } finally {
                                                c.setFocusable(wasFocusable);
                                            }
                                        });
                                        t.setRepeats(false);
                                        t.start();
                                    } catch (Exception e) {
                                        log.warn("AWT move+click scheduling failed: {}", e.toString());
                                    }
                                });
                                break;
                            }

                            case "key": {
                                if (cmd.k == null || cmd.k.isEmpty()) { out.println("{\"ok\":false,\"err\":\"need k\"}"); break; }
                                final String mode = config.mode().trim().toUpperCase();
                                final String k = cmd.k;

                                // AWT key events to canvas
                                javax.swing.SwingUtilities.invokeLater(() -> {
                                    try {
                                        final java.awt.Component c = (java.awt.Component) client.getCanvas();
                                        if (c == null) return;

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
                                        log.warn("[IPC] AWT key dispatch failed: {}", e.toString());
                                    }
                                });
                                out.println("{\"ok\":true,\"mode\":\"AWT\"}");
                                break;
                            }

                            case "keyHold": {
                                final String key = (cmd.key == null) ? "" : String.valueOf(cmd.key);
                                final int ms = (cmd.ms == null) ? 120 : Math.max(20, cmd.ms);
                                final String mode = config.mode().trim().toUpperCase();
                                out.println("Key hold started. Mode = " + mode + ", Key = " + key + ", ms = " + ms);

                                // AWT dispatch to the RuneLite canvas (works while window is showing, even if not OS-active)
                                new Thread(() -> {
                                    try {
                                        final java.awt.Component c = (java.awt.Component) client.getCanvas();
                                        if (c == null) return;
                                        final int vk = keyCodeFrom(key);
                                        if (vk < 0) { log.warn("[IPC] keyHold unknown key '{}'", key); return; }

                                        final long t0 = System.currentTimeMillis();
                                        java.awt.EventQueue.invokeAndWait(() -> {
                                            c.dispatchEvent(new java.awt.event.KeyEvent(
                                                    c, java.awt.event.KeyEvent.KEY_PRESSED, t0, 0, vk,
                                                    java.awt.event.KeyEvent.CHAR_UNDEFINED));
                                        });

                                        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}

                                        java.awt.EventQueue.invokeAndWait(() -> {
                                            c.dispatchEvent(new java.awt.event.KeyEvent(
                                                    c, java.awt.event.KeyEvent.KEY_RELEASED, System.currentTimeMillis(), 0, vk,
                                                    java.awt.event.KeyEvent.CHAR_UNDEFINED));
                                        });
                                    } catch (Exception e) {
                                        log.warn("[IPC] keyHold AWT failed: {}", e.toString());
                                    }
                                }, "ipc-keyhold-awt").start();

                                out.println("{\"ok\":true,\"mode\":\"AWT\",\"ms\":"+ms+"}");
                                break;
                            }


                            case "type": {
                                final String text = (cmd.text == null) ? "" : cmd.text;
                                final boolean pressEnter = cmd.enter != null && cmd.enter;
                                final int perCharMs = (cmd.perCharMs == null) ? 25 : Math.max(0, cmd.perCharMs);
                                final String mode = config.mode().trim().toUpperCase();

                                if (text.isEmpty()) { out.println("{\"ok\":false,\"err\":\"need text\"}"); break; }

                                out.println("{\"ok\":true,\"mode\":\"" + mode + "\",\"accepted\":\"type\"}");

                                new Thread(() -> {
                                    try {
                                        typeStringAWT(client, text, pressEnter, perCharMs);
                                    } catch (Throwable th) {
                                        log.warn("[IPC] type command failed: {}", th.toString());
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

                            // ===== PATHING =====

                            case "path": {
                                // Inputs (goal or rect). If both provided, rect wins.
                                final Integer gX = cmd.goalX;
                                final Integer gY = cmd.goalY;
                                final Integer rMinX = cmd.minX;
                                final Integer rMaxX = cmd.maxX;
                                final Integer rMinY = cmd.minY;
                                final Integer rMaxY = cmd.maxY;
                                final int maxWps = (cmd.maxWps != null) ? Math.max(1, cmd.maxWps) : 25;

                                // Optional label only for logs/overlay cosmetics
                                final String label =
                                        (rMinX != null && rMaxX != null && rMinY != null && rMaxY != null) ? "RECT" :
                                                (gX != null && gY != null) ? "GOAL" : "INVALID";

                                if (!("RECT".equals(label) || "GOAL".equals(label))) {
                                    out.println("{\"ok\":false,\"err\":\"need goalX,goalY or minX,maxX,minY,maxY\"}");
                                    break;
                                }

                                // Determine target world tile
                                WorldPoint startWp = null;
                                try { if (client.getLocalPlayer() != null) startWp = client.getLocalPlayer().getWorldLocation(); } catch (Exception ignored) {}

                                WorldPoint goalWp;
                                if ("RECT".equals(label)) {
                                    Rect R = new Rect(rMinX, rMaxX, rMinY, rMaxY);
                                    if (startWp == null) { out.println("{\"ok\":false,\"err\":\"no-player\"}"); break; }
                                    goalWp = pickGoalInRectNearestWalkable(startWp, R);
//                                    log.info("[IPC] path RECT: rect=[{}..{}]x[{}..{}] start={} goal={}",
//                                            rMinX, rMaxX, rMinY, rMaxY, startWp, goalWp);
                                } else {
                                    // GOAL
                                    if (gX == null || gY == null) { out.println("{\"ok\":false,\"err\":\"need goalX,goalY\"}"); break; }
                                    goalWp = new WorldPoint(gX, gY, client.getPlane());
//                                    log.info("[IPC] path GOAL: start={} goal=({}, {})", startWp, gX, gY);
                                }

                                // Compute scene path (BFS with 8-dir + no-corner-cut)
                                PathResult pr = computePathTowardScene(goalWp.getX(), goalWp.getY());
                                List<WorldPoint> full = (pr.path != null) ? pr.path : java.util.Collections.emptyList();
                                PathDebug dbg = pr.debug;

                                // Overlay (always show what we computed)
                                final WorldPoint fStart = startWp;
                                final WorldPoint fShownGoal = new WorldPoint(dbg.bestWx, dbg.bestWy, dbg.plane);
                                final int fLen = full.size();
                                clientThread.invokeLater(() -> {
                                    pathOverlay.setPath(full, fStart, fShownGoal);
                                });

                                // Waypoints for client: consecutive tiles, capped by maxWps
                                final int limit = Math.min(full.size(), Math.max(1, maxWps));

                                // Collect the points we will annotate
                                final List<WorldPoint> first = new ArrayList<>(limit);
                                for (int i = 0; i < limit; i++) {
                                    first.add(full.get(i));
                                }

                                // *** NEW: fetch door annotations on the client thread (single batch) ***
                                final int plane = client.getPlane(); // snapshot
                                // Budget ~100ms total for the batch; adjust to your tolerance.
                                // Even if it times out, we still build a normal response.
                                final Map<WorldPoint, Map<String, Object>> doorMap = getDoorAnnotationsOnClientThread(client, clientThread, first, plane, 100);

                                // Build the JSON waypoints, splicing in door info when available
                                final List<Map<String, Object>> wpsOut = new ArrayList<>(limit);
                                for (int i = 0; i < limit; i++) {
                                    final WorldPoint w = first.get(i);
                                    final Map<String, Object> m = new LinkedHashMap<>();
                                    m.put("x", w.getX());
                                    m.put("y", w.getY());
                                    m.put("p", w.getPlane());

                                    final Map<String, Object> door = doorMap.get(w);
                                    if (door != null) {
                                        m.put("door", door);
                                    }

                                    wpsOut.add(m);

                                }

                                // Identify the earliest blocking door across the returned segment
                                Map<String, Object> firstDoorInfo = null;
                                for (int wi = 0; wi < wpsOut.size(); wi++) {
                                    @SuppressWarnings("unchecked")
                                    final Map<String, Object> row = (Map<String, Object>) wpsOut.get(wi);
                                    @SuppressWarnings("unchecked")
                                    final Map<String, Object> door = (Map<String, Object>) row.get("door");
                                    if (door == null) continue;

                                    final boolean present = (door.get("present") instanceof Boolean) && ((Boolean) door.get("present"));
                                    if (!present) continue;

                                    final boolean closedKnown = (door.get("closed") instanceof Boolean);
                                    final boolean closed = closedKnown && ((Boolean) door.get("closed"));

                                    // Treat as blocking if we know it's closed OR we can't tell
                                    final boolean blocking = closed || !closedKnown;
                                    if (!blocking) continue;

                                    firstDoorInfo = new LinkedHashMap<>();
                                    firstDoorInfo.put("index", wi);
                                    firstDoorInfo.put("waypoint", java.util.Map.of(
                                            "x", (Integer)row.get("x"),
                                            "y", (Integer)row.get("y"),
                                            "p", (Integer)row.get("p")
                                    ));
                                    firstDoorInfo.put("door", door);

                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> click = (Map<String, Object>) door.get("canvas");
                                    if (click == null) {
                                        @SuppressWarnings("unchecked")
                                        Map<String, Object> tileClick = (Map<String, Object>) door.get("tileCanvas");
                                        click = tileClick;
                                    }
                                    if (click != null) {
                                        firstDoorInfo.put("click", click);
                                    }
                                    break;
                                }


                                // Build response
                                Map<String,Object> resp = new LinkedHashMap<>();
                                resp.put("ok", true);
                                resp.put("label", label);
                                resp.put("pathLen", full.size());
                                resp.put("returned", wpsOut.size());
                                resp.put("waypoints", wpsOut);
                                Map<String,Object> d = new LinkedHashMap<>();
                                d.put("whyFailed", dbg.whyFailed);
                                d.put("plane", dbg.plane);
                                d.put("base", java.util.Map.of("x", dbg.baseX, "y", dbg.baseY));
                                d.put("startW",  java.util.Map.of("x", dbg.startWx, "y", dbg.startWy));
                                d.put("goalW",   java.util.Map.of("x", dbg.goalWx,  "y", dbg.goalWy));
                                d.put("bestW",   java.util.Map.of("x", dbg.bestWx,  "y", dbg.bestWy));
                                d.put("goalInScene", dbg.goalInScene);
                                d.put("foundGoal",    dbg.foundGoal);
                                d.put("returnedBest", dbg.returnedBest);
                                d.put("expansions",   dbg.expansions);
                                d.put("timeMs",       dbg.timeMs);
                                resp.put("debug", d);

                                out.println(gson.toJson(resp));
                                break;
                            }

                            case "drag": {
                                final Integer fx = cmd.fromX, fy = cmd.fromY, tx = cmd.toX, ty = cmd.toY;
                                final int btn = (cmd.button == null) ? 3 : cmd.button;
                                final int hold = (cmd.holdMs == null) ? 120 : Math.max(0, cmd.holdMs);
                                if (fx == null || fy == null || tx == null || ty == null) { out.println("{\"ok\":false,\"err\":\"need fromX,fromY,toX,toY\"}"); break; }

                                javax.swing.SwingUtilities.invokeLater(() -> {
                                    try {
                                        final java.awt.Component c = (java.awt.Component) client.getCanvas();
                                        if (c == null) return;

                                        final int awtBtn = (btn == 3) ? java.awt.event.MouseEvent.BUTTON3
                                                : (btn == 2) ? java.awt.event.MouseEvent.BUTTON2
                                                :               java.awt.event.MouseEvent.BUTTON1;

                                        final boolean wasFocusable = c.isFocusable();
                                        try {
                                            c.setFocusable(false);

                                            long t0 = System.currentTimeMillis();
                                            c.dispatchEvent(new java.awt.event.MouseEvent(c, java.awt.event.MouseEvent.MOUSE_MOVED,   t0,   0, fx, fy, 0, false));
                                            c.dispatchEvent(new java.awt.event.MouseEvent(c, java.awt.event.MouseEvent.MOUSE_PRESSED, t0+1, 0, fx, fy, 1, false, awtBtn));
                                            c.dispatchEvent(new java.awt.event.MouseEvent(c, java.awt.event.MouseEvent.MOUSE_DRAGGED, t0+2, 0, tx, ty, 0, false));
                                            try { Thread.sleep(Math.max(0, hold)); } catch (InterruptedException ignored) {}
                                            c.dispatchEvent(new java.awt.event.MouseEvent(c, java.awt.event.MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0, tx, ty, 1, false, awtBtn));
                                        } finally {
                                            c.setFocusable(wasFocusable);
                                        }
                                    } catch (Exception e) {
                                        log.warn("[IPC] drag failed: {}", e.toString());
                                    }
                                });
                                out.println("{\"ok\":true}");
                                break;
                            }

                            case "scroll": {
                                final int amt = (cmd.amount == null) ? 0 : cmd.amount;
                                log.info("[IPC] scroll received amt={} (sign: {} -> {} zoom)",
                                        amt, (amt<0?"neg":"pos"), (amt<0?"OUT":"IN"));
                                javax.swing.SwingUtilities.invokeLater(() -> {
                                    try {
                                        final java.awt.Component c = (java.awt.Component) client.getCanvas();
                                        if (c == null) { log.warn("[IPC] scroll: canvas null"); return; }
                                        long now = System.currentTimeMillis();
                                        java.awt.event.MouseWheelEvent wheel = new java.awt.event.MouseWheelEvent(
                                                c, java.awt.event.MouseEvent.MOUSE_WHEEL, now, 0,
                                                c.getWidth()/2, c.getHeight()/2, 0, false,
                                                java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL, Math.abs(amt),
                                                (amt < 0) ? -1 : 1
                                        );
                                        log.info("[IPC] dispatch MouseWheelEvent rotations={} wheelRotation={}",
                                                Math.abs(amt), ((amt < 0) ? -1 : 1));
                                        c.dispatchEvent(wheel);
                                    } catch (Exception e) {
                                        log.warn("[IPC] scroll failed: {}", e.toString());
                                    }
                                });
                                out.println("{\"ok\":true,\"cmd\":\"scroll\",\"amt\":"+amt+"}");
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
                        // keep server alive
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

            // Build world points + remember which rows map to which points
            final List<WorldPoint> points = new ArrayList<>();
            final List<int[]> coords = new ArrayList<>(); // [wx, wy]

            for (Map<String,Integer> t : tiles) {
                Integer wx = t.get("x"), wy = t.get("y");
                if (wx == null || wy == null) {
                    out.add(java.util.Map.of("ok", false, "err", "bad-tile"));
                    continue;
                }
                coords.add(new int[]{wx, wy});
                points.add(new WorldPoint(wx, wy, plane));
            }

            // Batch door annotations on client thread (safe)
            final Map<WorldPoint, Map<String, Object>> doorMap =
                    getDoorAnnotationsOnClientThread(client, clientThread, points, plane, 100);

            // Now do the canvas projection and merge door data per row
            for (int i = 0; i < coords.size(); i++) {
                int[] xy = coords.get(i);
                int wx = xy[0], wy = xy[1];

                LocalPoint lp = LocalPoint.fromWorld(client, wx, wy);
                if (lp == null) { out.add(java.util.Map.of("ok", true, "onscreen", false)); continue; }

                net.runelite.api.Point p = Perspective.localToCanvas(client, lp, plane);
                if (p == null) { out.add(java.util.Map.of("ok", true, "onscreen", false)); continue; }

                Map<String,Object> row = new LinkedHashMap<>();
                row.put("ok", true);
                row.put("onscreen", true);
                row.put("canvas", java.util.Map.of("x", p.getX(), "y", p.getY()));

                // Attach door when present
                Map<String,Object> door = doorMap.get(new WorldPoint(wx, wy, plane));
                if (door != null) {
                    row.put("door", door);
                }

                out.add(row);
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
            resp.put("rows", rows); // north-to-south
            return resp;
        }

        /* ======================= PATHFINDING ======================= */

        private static boolean inScene(int lx, int ly) {
            return lx >= 0 && ly >= 0 && lx < 104 && ly < 104;
        }

        private static boolean isWalkableLocal(int[][] flags, int lx, int ly) {
            int f = flags[lx][ly];
            boolean solid  = (f & 0x200000) != 0; // SOLID tile
            boolean object = (f & 0x000100) != 0; // OBJECT present
            return !(solid || object);
        }

        private static int manhattan(int x0,int y0,int x1,int y1){ return Math.abs(x1-x0)+Math.abs(y1-y0); }

        // Nearest walkable tile to (lGoal) within radius using ring-BFS on scene flags.
        private int[] nearestWalkableLocal(int[][] flags, int lGoalX, int lGoalY, int maxR) {
            if (inScene(lGoalX, lGoalY) && isWalkableLocal(flags, lGoalX, lGoalY))
                return new int[]{lGoalX, lGoalY};

            boolean[][] seen = new boolean[104][104];
            ArrayDeque<int[]> dq = new ArrayDeque<>();
            if (inScene(lGoalX, lGoalY)) {
                dq.add(new int[]{lGoalX, lGoalY, 0});
                seen[lGoalX][lGoalY] = true;
            } else {
                // If goal is off-scene, no scan possible here
                return null;
            }

            int[] dirs = { 1,0,-1,0,0,1,0,-1 };
            while (!dq.isEmpty()) {
                int[] cur = dq.removeFirst();
                int x = cur[0], y = cur[1], d = cur[2];
                if (d > maxR) break;

                if (isWalkableLocal(flags, x, y)) return new int[]{x,y};

                for (int i = 0; i < 8; i += 2) {
                    int nx = x + dirs[i], ny = y + dirs[i+1];
                    if (!inScene(nx, ny) || seen[nx][ny]) continue;
                    seen[nx][ny] = true;
                    dq.addLast(new int[]{nx, ny, d+1});
                }
            }
            return null;
        }

        // Pick a goal inside the rect, preferring nearest WALKABLE tile to 'from' (if in-scene).
        private WorldPoint pickGoalInRectNearestWalkable(WorldPoint from, Rect rect) {
            int plane = client.getPlane();

            // Center first, but clamp within rect
            int cx = (rect.minX + rect.maxX) / 2;
            int cy = (rect.minY + rect.maxY) / 2;
            int gx = Math.max(rect.minX, Math.min(rect.maxX, cx));
            int gy = Math.max(rect.minY, Math.min(rect.maxY, cy));
            WorldPoint initial = new WorldPoint(gx, gy, plane);

            var cms = client.getCollisionMaps();
            if (cms == null || plane < 0 || plane >= cms.length || cms[plane] == null)
                return initial; // no flags, just use center

            int[][] flags = cms[plane].getFlags();
            int baseX = client.getBaseX(), baseY = client.getBaseY();
            int lGoalX = gx - baseX, lGoalY = gy - baseY;

            // If the goal is inside the scene, try to snap it to nearest WALKABLE
            if (inScene(lGoalX, lGoalY)) {
                int[] best = nearestWalkableLocal(flags, lGoalX, lGoalY, 20);
                if (best != null) {
                    int bwx = best[0] + baseX, bwy = best[1] + baseY;
                    // Keep within rect bounds
                    bwx = Math.max(rect.minX, Math.min(rect.maxX, bwx));
                    bwy = Math.max(rect.minY, Math.min(rect.maxY, bwy));
                    return new WorldPoint(bwx, bwy, plane);
                }
            }
            return initial;
        }

        private static java.util.List<String> dump9x9(int[][] flags, int cx, int cy) {
            java.util.ArrayList<String> rows = new java.util.ArrayList<>(9);
            for (int y = cy + 4; y >= cy - 4; y--) {
                StringBuilder sb = new StringBuilder(9);
                for (int x = cx - 4; x <= cx + 4; x++) {
                    char ch = '#';
                    if (inScene(x, y)) {
                        int f = flags[x][y];
                        boolean solid = (f & 0x200000) != 0;
                        boolean object = (f & 0x000100) != 0;
                        ch = (solid || object) ? '#' : '.';
                    }
                    if (x == cx && y == cy) ch = 'P';
                    sb.append(ch);
                }
                rows.add(sb.toString());
            }
            return rows;
        }


        // BFS inside current scene: if true goal is off-scene/unreachable, return path to best on-scene tile toward goal
        private PathResult computePathTowardScene(int goalWx, int goalWy)
        {
            final long t0 = System.currentTimeMillis();
            PathDebug dbg = new PathDebug();
            dbg.maxExpansions = 20000;

            var lp = client.getLocalPlayer();
            if (lp == null) {
                dbg.whyFailed = "no-player";
                log.warn("[IPC] path: no-player");
                return new PathResult(java.util.Collections.emptyList(), dbg);
            }

            dbg.plane = client.getPlane();
            var cms = client.getCollisionMaps();
            if (cms == null || dbg.plane < 0 || dbg.plane >= cms.length || cms[dbg.plane] == null) {
                dbg.whyFailed = "no-collision-maps-for-plane";
                log.warn("[IPC] path: no collision maps for plane={}", dbg.plane);
                return new PathResult(java.util.Collections.emptyList(), dbg);
            }
            final int[][] flags = cms[dbg.plane].getFlags();

            dbg.baseX = client.getBaseX();
            dbg.baseY = client.getBaseY();

            dbg.startWx = lp.getWorldLocation().getX();
            dbg.startWy = lp.getWorldLocation().getY();
            dbg.goalWx  = goalWx;
            dbg.goalWy  = goalWy;

            dbg.startLx = dbg.startWx - dbg.baseX;
            dbg.startLy = dbg.startWy - dbg.baseY;
            dbg.goalLx  = goalWx - dbg.baseX;
            dbg.goalLy  = goalWy - dbg.baseY;

            dbg.startInScene = inScene(dbg.startLx, dbg.startLy);
            dbg.goalInScene  = inScene(dbg.goalLx,  dbg.goalLy);

//            log.info("[IPC] computePathTowardScene: plane={} base=({}, {}) startW=({}, {}) startL=({}, {}) goalW=({}, {}) goalL=({}, {}) goalInScene={}",
//                    dbg.plane, dbg.baseX, dbg.baseY,
//                    dbg.startWx, dbg.startWy, dbg.startLx, dbg.startLy,
//                    dbg.goalWx, dbg.goalWy, dbg.goalLx, dbg.goalLy, dbg.goalInScene);

            if (!dbg.startInScene) {
                dbg.whyFailed = "start-off-scene";
                log.warn("[IPC] path: start off-scene at local=({}, {})", dbg.startLx, dbg.startLy);
                return new PathResult(java.util.Collections.emptyList(), dbg);
            }

            dbg.startWalkable = isWalkableLocal(flags, dbg.startLx, dbg.startLy);
            if (!dbg.startWalkable) {
                dbg.whyFailed = "start-blocked";
                log.warn("[IPC] path: start blocked at local=({}, {})", dbg.startLx, dbg.startLy);
                return new PathResult(java.util.Collections.emptyList(), dbg);
            }

            // If goal is in-scene but blocked, snap to nearest walkable.
            if (dbg.goalInScene && !isWalkableLocal(flags, dbg.goalLx, dbg.goalLy)) {
                int[] near = nearestWalkableLocal(flags, dbg.goalLx, dbg.goalLy, 20);
                if (near != null) {
                    log.warn("[IPC] path: goal blocked at local=({},{}); snapped to nearest walkable local=({},{}).",
                            dbg.goalLx, dbg.goalLy, near[0], near[1]);
                    dbg.goalLx = near[0]; dbg.goalLy = near[1];
                    dbg.goalWx = dbg.goalLx + dbg.baseX; dbg.goalWy = dbg.goalLy + dbg.baseY;
                } else {
                    log.warn("[IPC] path: goal blocked and no walkable neighbor found within radius.");
                }
            }

            // Small diagnostics around start/goal
            dbg.start9x9 = dump9x9(flags, dbg.startLx, dbg.startLy);
            if (dbg.goalInScene) {
                dbg.start9x9 = dump9x9(flags, dbg.startLx, dbg.startLy);
            }

            final int W = 104, H = 104;
            int[][] prev = new int[W][H];      // pack as (px<<16)|py
            boolean[][] seen = new boolean[W][H];

            // Track best in-scene tile toward the goal (Manhattan)
            int bestLx = dbg.startLx, bestLy = dbg.startLy;
            int bestMan = manhattan(dbg.startLx, dbg.startLy, dbg.goalLx, dbg.goalLy);
            seen[dbg.startLx][dbg.startLy] = true;

            ArrayDeque<int[]> dq = new ArrayDeque<>();
            dq.add(new int[]{dbg.startLx, dbg.startLy});

            // 8 neighbors: E, W, N, S, and the 4 diagonals
            final int[] DIRS8 = { 1,0,  -1,0,  0,1,  0,-1,   1,1,   1,-1,   -1,1,   -1,-1 };

            boolean reachedTrueGoal = false;
            int expansions = 0;
            int skippedBlocked = 0, skippedCorner = 0, enqueued = 0;

            while (!dq.isEmpty() && expansions++ < dbg.maxExpansions) {
                int[] cur = dq.removeFirst();
                int cx = cur[0], cy = cur[1];

                // Update best candidate
                int m = manhattan(cx, cy, dbg.goalLx, dbg.goalLy);
                if (m < bestMan) {
                    bestMan = m; bestLx = cx; bestLy = cy;
                }

                // If the goal tile is in-scene and we reached it -> done
                if (dbg.goalInScene && cx == dbg.goalLx && cy == dbg.goalLy) {
                    reachedTrueGoal = true;
                    break;
                }

                for (int i = 0; i < DIRS8.length; i += 2) {
                    int dx = DIRS8[i], dy = DIRS8[i+1];
                    int nx = cx + dx, ny = cy + dy;

                    if (!inScene(nx, ny) || seen[nx][ny]) continue;

                    // Destination must be walkable
                    if (!isWalkableLocal(flags, nx, ny)) { skippedBlocked++; continue; }

                    // If diagonal, enforce no-corner-cutting:
                    // both adjacent cardinals must be walkable: (cx+dx, cy) and (cx, cy+dy)
                    if (dx != 0 && dy != 0) {
                        int adj1x = cx + dx, adj1y = cy;
                        int adj2x = cx,       adj2y = cy + dy;

                        if (!inScene(adj1x, adj1y) || !inScene(adj2x, adj2y)
                                || !isWalkableLocal(flags, adj1x, adj1y)
                                || !isWalkableLocal(flags, adj2x, adj2y)) {
                            skippedCorner++;
                            continue;
                        }
                    }

                    seen[nx][ny] = true;
                    prev[nx][ny] = (cx << 16) | (cy & 0xFFFF);
                    dq.addLast(new int[]{nx, ny});
                    enqueued++;
                }
            }

            dbg.expansions = expansions;
            dbg.foundGoal = reachedTrueGoal;
            dbg.extraCounters = new java.util.LinkedHashMap<>();
            dbg.extraCounters.put("skippedBlocked", skippedBlocked);
            dbg.extraCounters.put("skippedCorner",  skippedCorner);
            dbg.extraCounters.put("enqueued",       enqueued);
            dbg.extraCounters.put("seenCount",      countSeen(seen));

            // Choose endpoint to reconstruct
            int endLx, endLy;
            if (reachedTrueGoal) {
                endLx = dbg.goalLx; endLy = dbg.goalLy;
                dbg.returnedBest = false;
                dbg.whyFailed = null;
            } else {
                endLx = bestLx; endLy = bestLy;
                dbg.returnedBest = true;
                if (!dbg.goalInScene) dbg.whyFailed = "goal-off-scene-partial-returned";
                else dbg.whyFailed = "no-path-to-goal-partial-returned";
            }

            dbg.bestLx = endLx; dbg.bestLy = endLy;
            dbg.bestWx = endLx + dbg.baseX; dbg.bestWy = endLy + dbg.baseY;
            dbg.bestManhattan = manhattan(endLx, endLy, dbg.goalLx, dbg.goalLy);
            dbg.best9x9 = dump9x9(flags, endLx, endLy);

            // Reconstruct path
            ArrayList<WorldPoint> path = new ArrayList<>();
            if (!(endLx == dbg.startLx && endLy == dbg.startLy)) {
                int px = endLx, py = endLy;
                while (!(px == dbg.startLx && py == dbg.startLy)) {
                    path.add(new WorldPoint(px + dbg.baseX, py + dbg.baseY, dbg.plane));
                    int packed = prev[px][py];
                    int ppx = (packed >>> 16);
                    int ppy = (packed & 0xFFFF);
                    px = ppx; py = ppy;
                }
                java.util.Collections.reverse(path);
            }

            dbg.timeMs = System.currentTimeMillis() - t0;

//            log.info("[IPC] BFS done: foundGoal={} returnedBest={} expansions={} enqueued={} skippedBlocked={} skippedCorner={} seen={} endL=({}, {}) endW=({}, {}) pathLen={} timeMs={}",
//                    dbg.foundGoal, dbg.returnedBest, dbg.expansions, enqueued, skippedBlocked, skippedCorner,
//                    dbg.extraCounters.get("seenCount"),
//                    endLx, endLy, dbg.bestWx, dbg.bestWy,
//                    (path == null ? 0 : path.size()), dbg.timeMs);

            if (!dbg.foundGoal) {
                if (dbg.start9x9 != null) {
                    log.info("[IPC] start9x9 @ ({},{}):\n{}", dbg.startLx, dbg.startLy, String.join("\n", dbg.start9x9));
                }
                if (dbg.goalInScene && dbg.goal9x9 != null) {
                    log.info("[IPC] goal9x9  @ ({},{}):\n{}", dbg.goalLx, dbg.goalLy, String.join("\n", dbg.goal9x9));
                }
                if (dbg.best9x9 != null) {
                    log.info("[IPC] best9x9  @ ({},{}):\n{}", dbg.bestLx, dbg.bestLy, String.join("\n", dbg.best9x9));
                }
            }


            return new PathResult(path, dbg);
        }

        /** Count how many tiles were marked seen=true. */
        private static int countSeen(boolean[][] seen) {
            int c = 0;
            for (int x = 0; x < seen.length; x++) {
                for (int y = 0; y < seen[x].length; y++) {
                    if (seen[x][y]) c++;
                }
            }
            return c;
        }


        /* ======================= KEY/TYPE HELPERS ======================= */

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

                // NEW: arrows & common nav
                case "LEFT":   return java.awt.event.KeyEvent.VK_LEFT;
                case "RIGHT":  return java.awt.event.KeyEvent.VK_RIGHT;
                case "UP":     return java.awt.event.KeyEvent.VK_UP;
                case "DOWN":   return java.awt.event.KeyEvent.VK_DOWN;
                case "PAGEUP":   return java.awt.event.KeyEvent.VK_PAGE_UP;
                case "PAGEDOWN": return java.awt.event.KeyEvent.VK_PAGE_DOWN;

                default:
                    char ch = k.toUpperCase().charAt(0);
                    return java.awt.event.KeyEvent.getExtendedKeyCodeForChar(ch);
            }
        }


        private void typeStringAWT(Client client,
                                   String text,
                                   boolean pressEnter,
                                   int perCharMs)
        {
            try {
                final java.awt.Component c = (java.awt.Component) client.getCanvas();
                if (c == null) return;

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
                log.warn("[IPC] typeStringAWT error: {}", th.toString());
            }
        }

        // Batch door annotations for multiple world points on the client thread.
        // plane: use client.getPlane() for typical use. timeoutMs is total budget for the whole batch.
        private Map<WorldPoint, Map<String, Object>> getDoorAnnotationsOnClientThread(
                Client client,
                ClientThread clientThread,
                List<WorldPoint> points,
                int plane,
                int timeoutMs
        ) {
            final CompletableFuture<Map<WorldPoint, Map<String, Object>>> fut = new CompletableFuture<>();

            clientThread.invokeLater(() -> {
                final Map<WorldPoint, Map<String, Object>> out = new LinkedHashMap<>();
                try {
                    final Scene scene = client.getScene();
                    if (scene == null) {
                        fut.complete(out);
                        return;
                    }
                    final int baseX = client.getBaseX();
                    final int baseY = client.getBaseY();
                    final Tile[][][] tilesArr = scene.getTiles();

                    if (tilesArr == null || plane < 0 || plane >= tilesArr.length || tilesArr[plane] == null) {
                        fut.complete(out);
                        return;
                    }

                    for (WorldPoint w : points) {
                        final int wx = w.getX(), wy = w.getY();
                        final int lx = wx - baseX, ly = wy - baseY;
                        if (lx < 0 || lx >= 104 || ly < 0 || ly >= 104) {
                            continue;
                        }

                        final Tile tile = tilesArr[plane][lx][ly];
                        final WallObject wobj = (tile != null) ? tile.getWallObject() : null;
                        if (wobj == null) {
                            continue;
                        }

                        // This MUST be on the client thread; otherwise RuneLite throws "must be called on client thread".
                        final ObjectComposition comp = client.getObjectDefinition(wobj.getId());
                        final String name = (comp != null) ? comp.getName() : null;
                        final String nm = (name != null) ? name.toLowerCase() : null;

// Treat "door", "gate" (and any composite names) as doors
                        final boolean looksLikeDoor = (nm != null) && (nm.contains("door") || nm.contains("gate"));
                        if (!looksLikeDoor) {
                            continue;
                        }

                        boolean hasOpen = false, hasClose = false;
                        final String[] acts = (comp != null) ? comp.getActions() : null;
                        if (acts != null) {
                            for (String a : acts) {
                                if (a == null) continue;
                                final String s = a.toLowerCase();
                                if (s.equals("open"))  hasOpen  = true;
                                if (s.equals("close")) hasClose = true;
                            }
                        }
// If "Open" is present and "Close" isn't, we assume it's currently closed.
                        final boolean closed = hasOpen && !hasClose;

                        final Map<String, Object> door = new LinkedHashMap<>();
                        door.put("present", true);
                        door.put("closed", closed);
                        door.put("id", wobj.getId());
                        if (comp != null) {
                            door.put("name", comp.getName());
                        }

// Optional canvas bounds via convex hull (orientation hint too)
                        try {
                            final java.awt.Shape hull = wobj.getConvexHull();
                            if (hull != null) {
                                final java.awt.Rectangle rb = hull.getBounds();
                                if (rb != null) {
                                    door.put("bounds", java.util.Map.of(
                                            "x", rb.x, "y", rb.y, "width", rb.width, "height", rb.height
                                    ));
                                    door.put("canvas", java.util.Map.of(
                                            "x", rb.x + rb.width / 2,
                                            "y", rb.y + rb.height / 2
                                    ));
                                    // crude but effective orientation hint
                                    door.put("orientation", (rb.width > rb.height) ? "horizontal" : "vertical");
                                }
                            }
                        } catch (Exception ignored) {}

// Fallback: tile center projection (in case convex hull was null)
                        try {
                            final LocalPoint lp = LocalPoint.fromWorld(client, wx, wy);
                            if (lp != null) {
                                final net.runelite.api.Point pt = Perspective.localToCanvas(client, lp, plane);
                                if (pt != null) {
                                    door.put("tileCanvas", java.util.Map.of("x", pt.getX(), "y", pt.getY()));
                                }
                            }
                        } catch (Exception ignored) {}

                        out.put(w, door);
                    }
                } catch (Throwable t) {
                    // Swallow any assertion/other errors to avoid killing IPC; we’ll return what we have.
                } finally {
                    fut.complete(out);
                }
            });

            try {
                // Wait for the client-thread work to finish, but don't block forever.
                return fut.get(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                return Collections.emptyMap();
            }
        }


        /* ======================= CMD STRUCT ======================= */

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

            // path
            @SerializedName("goalX") Integer goalX;
            @SerializedName("goalY") Integer goalY;
            @SerializedName("maxWps") Integer maxWps;

            // mask
            @SerializedName("radius") Integer radius;

            // tilexy_many
            @SerializedName("tiles") List<Map<String,Integer>> tiles;

            // for path_rect
            @SerializedName("minX") Integer minX;
            @SerializedName("maxX") Integer maxX;
            @SerializedName("minY") Integer minY;
            @SerializedName("maxY") Integer maxY;

            // NEW: drag
            @SerializedName("fromX") Integer fromX;
            @SerializedName("fromY") Integer fromY;
            @SerializedName("toX")   Integer toX;
            @SerializedName("toY")   Integer toY;
            @SerializedName("holdMs") Integer holdMs;

            // NEW: scroll
            @SerializedName("amount") Integer amount;

            // inside IpcInputPlugin.ServerThread.Cmd
            @SerializedName("key") String key;   // e.g. "LEFT", "RIGHT", "esc"
            @SerializedName("ms")  Integer ms;   // hold duration in milliseconds

        }
    }
}
