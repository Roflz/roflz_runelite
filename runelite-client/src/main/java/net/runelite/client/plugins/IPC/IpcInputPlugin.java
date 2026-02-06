package net.runelite.client.plugins.ipcinput;

import java.awt.Component;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.Base64;
import javax.imageio.ImageIO;
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
import net.runelite.client.game.ItemManager;
//import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.WorldService;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.api.events.MenuOptionClicked;
import static net.runelite.api.CollisionDataFlag.*;


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
    @Inject private WorldService worldService;
    @Inject private ItemManager itemManager;

    @Inject private OverlayManager overlayManager;
    // private CollisionOverlay collisionOverlay; // Disabled - was causing lag
    private PathOverlay pathOverlay;

    private ServerThread serverThread;

    // Last interaction data for click verification
    private Map<String, Object> lastInteraction = null;

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

        // collisionOverlay = new CollisionOverlay(client); // Disabled - was causing lag
        pathOverlay = new PathOverlay(client);
        // overlayManager.add(collisionOverlay); // Disabled - was causing lag
        overlayManager.add(pathOverlay);

        try {
            serverThread = new ServerThread(this, client, clientThread, config, port, pathOverlay, itemManager);
            serverThread.start();
            log.info("IPC Input listening on {}", port);
            // Do not post chat message here: it fires ChatMessage events before other plugins
            // (LootTracker, Screenshot) are ready, causing NPEs. Logging above is sufficient.
        } catch (RuntimeException ex) {
            log.error("IPC Input failed to bind port {}: {}", port, ex.getMessage());
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
        // if (collisionOverlay != null) // Disabled - was causing lag
        // {
        //     overlayManager.remove(collisionOverlay);
        //     collisionOverlay = null;
        // }

        log.info("IPC Input stopped");
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event) {
        if (event.getMenuOption() != null && event.getMenuTarget() != null) {
            log.debug("Menu clicked: {} on {}", event.getMenuOption(), event.getMenuTarget());
            
            // Simple interaction tracking - only essential info
            Map<String, Object> interaction = new HashMap<>();
            interaction.put("action", event.getMenuOption());
            interaction.put("target", event.getMenuTarget());
            interaction.put("timestamp", System.currentTimeMillis());
            
            // Extract target name (item/object/npc name)
            String targetName = event.getMenuTarget();
            if (targetName.contains(">")) {
                targetName = targetName.split(">")[1].trim();
            }
            interaction.put("target_name", targetName);
            
            // Add mouse coordinates
            net.runelite.api.Point mousePos = client.getMouseCanvasPosition();
            Map<String, Object> mouseCoords = new HashMap<>();
            mouseCoords.put("canvasX", mousePos.getX());
            mouseCoords.put("canvasY", mousePos.getY());
            interaction.put("mouse_coordinates", mouseCoords);
            
            // Add world coordinates of the click
            Tile clickedTile = client.getSelectedSceneTile();
            if (clickedTile != null) {
                WorldPoint worldPos = clickedTile.getWorldLocation();
                Map<String, Object> worldCoords = new HashMap<>();
                worldCoords.put("worldX", worldPos.getX());
                worldCoords.put("worldY", worldPos.getY());
                worldCoords.put("plane", worldPos.getPlane());
                interaction.put("world_coordinates", worldCoords);
            } else {
                interaction.put("world_coordinates", null);
            }
            
            // Store the interaction
            lastInteraction = interaction;
        }
    }

    /**
     * Get the last interaction data captured by the plugin.
     * @return Map containing the last interaction data, or null if no interaction has been captured
     */
    public Map<String, Object> getLastInteraction() {
        return lastInteraction;
    }

    /* ======================= Server Thread ======================= */

    private static final class ServerThread extends Thread
    {
        private final IpcInputPlugin plugin;
        private final Client client;
        private final ClientThread clientThread;
        private final IpcInputConfig config;
        private final int port;
        private final PathOverlay pathOverlay;
        private final ItemManager itemManager;

        private final Gson gson = new Gson();
        private volatile boolean running = true;
        private ServerSocket server;

        // Add near the top of ServerThread (helpers)
        private static String safeSnippet(String s, int max) {
            if (s == null) return null;
            s = s.replace("\n", "\\n").replace("\r", "\\r");
            return (s.length() <= max) ? s : s.substring(0, max) + "...";
        }
        private static String safeMsg(Throwable t) {
            return (t == null || t.getMessage() == null) ? t.toString() : t.getMessage();
        }
        
        /**
         * Encode item image to base64 string.
         * Returns null if image cannot be retrieved or encoded.
         */
        private String encodeItemImageToBase64(int itemId) {
            try {
                BufferedImage image = itemManager.getImage(itemId);
                if (image == null) {
                    return null;
                }
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(image, "PNG", baos);
                byte[] imageBytes = baos.toByteArray();
                return Base64.getEncoder().encodeToString(imageBytes);
            } catch (Exception e) {
                // Silently fail - icon is optional
                return null;
            }
        }

        /**
         * Resolve the effective ObjectComposition for a TileObject.
         *
         * Some objects (notably certain GroundObjects like the MLM Sack) have a base definition
         * whose name/actions are "null", but a real, interactable definition via getImpostor().
         *
         * Priority:
         *  1) comp.getImpostor() if non-null
         *  2) fallback scan of impostorIds for a non-null name
         *  3) base comp
         */
        private static ObjectComposition resolveObjectComposition(Client client, ObjectComposition comp) {
            if (comp == null) return null;
            try {
                final ObjectComposition imp = comp.getImpostor();
                if (imp != null) return imp;
            } catch (Exception ignored) {}

            try {
                final String nm = comp.getName();
                if (nm == null || nm.isEmpty() || "null".equalsIgnoreCase(nm)) {
                    final int[] impostorIds = comp.getImpostorIds();
                if (impostorIds != null && impostorIds.length > 0) {
                    for (int impostorId : impostorIds) {
                            if (impostorId == -1) continue;
                            final ObjectComposition ic = client.getObjectDefinition(impostorId);
                            if (ic == null) continue;
                            final String in = ic.getName();
                            if (in != null && !in.isEmpty() && !"null".equalsIgnoreCase(in)) {
                                return ic;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}

            return comp;
        }

        private static java.util.Map<String,Object> createObjectData(TileObject obj, String objectType, int baseX, int baseY, int plane, Client client, String needle) {
            final java.util.Map<String,Object> objData = new java.util.LinkedHashMap<>();
            
            final int id = obj.getId();
            final ObjectComposition baseComp = client.getObjectDefinition(id);
            final ObjectComposition comp = resolveObjectComposition(client, baseComp);
            String nm = (comp != null && comp.getName() != null) ? comp.getName() : "";
            String[] actions = (comp != null) ? comp.getActions() : null;

            final int wx = baseX + obj.getLocalLocation().getSceneX();
            final int wy = baseY + obj.getLocalLocation().getSceneY();
            final int distance = Math.abs(wx - client.getLocalPlayer().getWorldLocation().getX()) + Math.abs(wy - client.getLocalPlayer().getWorldLocation().getY());

            objData.put("id", id);
            objData.put("type", objectType);
            objData.put("name", nm);
            objData.put("world", java.util.Map.of("x", wx, "y", wy, "p", plane));
            objData.put("distance", distance);
            objData.put("exactMatch", nm.toLowerCase().equals(needle));

            if (actions != null) {
                java.util.List<String> al = new java.util.ArrayList<>();
                for (String a : actions) if (a != null) al.add(a);
                objData.put("actions", al);
            }

            // Get bounds/canvas position
            try {
                final java.awt.Shape hull = (obj instanceof GameObject) ? ((GameObject)obj).getConvexHull()
                        : (obj instanceof DecorativeObject) ? ((DecorativeObject)obj).getConvexHull()
                        : (obj instanceof WallObject) ? ((WallObject)obj).getConvexHull()
                        : (obj instanceof GroundObject) ? ((GroundObject)obj).getConvexHull()
                        : null;
                if (hull != null) {
                    final java.awt.Rectangle rb = hull.getBounds();
                    if (rb != null) {
                        objData.put("bounds", java.util.Map.of(
                                "x", rb.x, "y", rb.y, "width", rb.width, "height", rb.height));
                        objData.put("canvas", java.util.Map.of(
                                "x", rb.x + rb.width/2, "y", rb.y + rb.height/2));
                        objData.put("orientation", (rb.width > rb.height) ? "horizontal" : "vertical");
                    }
                }
            } catch (Exception ignored) {}

            // Fallback: tile center projection
            try {
                final LocalPoint lp = LocalPoint.fromWorld(client, wx, wy);
                if (lp != null) {
                    final net.runelite.api.Point pt = Perspective.localToCanvas(client, lp, plane);
                    if (pt != null) objData.put("tileCanvas", java.util.Map.of("x", pt.getX(), "y", pt.getY()));
                }
            } catch (Exception ignored) {}

            return objData;
        }


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

        ServerThread(IpcInputPlugin plugin, Client client, ClientThread clientThread, IpcInputConfig config, int port, PathOverlay pathOverlay, ItemManager itemManager)
        {
            super("IPC-Input-Server");
            this.plugin = plugin;
            setDaemon(true);
            this.client = client;
            this.clientThread = clientThread;
            this.config = config;
            this.port = port;
            this.pathOverlay = pathOverlay;
            this.itemManager = itemManager;

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
                        if (line == null) {
                            out.println("{\"ok\":false,\"err\":\"empty-line\"}");
                            continue;
                        }
                        log.info("[IPC] recv raw: {}", safeSnippet(line, 256));

                        final Cmd cmd;
                        try {
                            cmd = gson.fromJson(line.trim(), Cmd.class);
                        } catch (Exception parseEx) {
                            out.println("{\"ok\":false,\"err\":\"bad-json\",\"detail\":\""
                                    + safeSnippet(safeMsg(parseEx), 160) + "\",\"raw\":\""
                                    + safeSnippet(line, 200) + "\"}");
                            continue;
                        }
                        if (cmd == null || cmd.cmd == null) {
                            out.println("{\"ok\":false,\"err\":\"missing-cmd\"}");
                            continue;
                        }

//                        log.info("[IPC] cmd={}", cmd.cmd);

                        switch (cmd.cmd)
                        {
                            case "ping": {
                                final java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
                                resp.put("ok", true);
                                resp.put("ts", System.currentTimeMillis());
                                try { resp.put("plane", client.getPlane()); } catch (Throwable ignored) {}
                                try {
                                    if (client.getLocalPlayer() != null) {
                                        resp.put("player", java.util.Map.of(
                                                "x", client.getLocalPlayer().getWorldLocation().getX(),
                                                "y", client.getLocalPlayer().getWorldLocation().getY(),
                                                "p", client.getLocalPlayer().getWorldLocation().getPlane()
                                        ));
                                    }
                                } catch (Throwable ignored) {}
                                // Advertise supported cmds to help you spot version skew
                                resp.put("cmds", new String[]{"ping","click","scroll","path","project","objects","npcs","tab","hovered","widget_exists","get_widget","get_widget_info","get_widget_children","get_bank_items","get_bank_tabs","get_bank_quantity_buttons","get_bank_deposit_buttons","get_bank_note_toggle","get_bank_search","bank-xvalue","get_ge_widgets","get_ge_offers","get_ge_setup","get_ge_confirm","get_ge_buttons","door_state","get_player","get_equipment","get_equipment_inventory","get_spellbook","get_camera","find_object","find_object_by_path","find_npc","scan_scene","detect_water","get_tutorial","get_game_state","get_world","get_worlds","hop_world"});
                                out.println(gson.toJson(resp));
                                break;
                            }


                            case "port":
                                out.println("{\"ok\":true,\"port\":" + port + "}");
                                break;

                            case "get_game_state": {
                                final java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
                                try {
                                    GameState gameState = client.getGameState();
                                    resp.put("ok", true);
                                    resp.put("state", gameState.toString());
                                } catch (Throwable t) {
                                    resp.put("ok", false);
                                    resp.put("err", "get-game-state-failed");
                                }
                                out.println(gson.toJson(resp));
                                break;
                            }

                            case "get_world": {
                                final java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
                                try {
                                    int worldNumber = client.getWorld();
                                    resp.put("ok", true);
                                    resp.put("world", worldNumber);
                                } catch (Throwable t) {
                                    resp.put("ok", false);
                                    resp.put("err", "get-world-failed");
                                }
                                out.println(gson.toJson(resp));
                                break;
                            }

                            case "get_worlds": {
                                final java.util.concurrent.CompletableFuture<java.util.Map<String,Object>> fut =
                                        new java.util.concurrent.CompletableFuture<>();

                                clientThread.invokeLater(() -> {
                                    final java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
                                    try {
                                        final net.runelite.http.api.worlds.WorldResult wr =
                                                (plugin.worldService != null) ? plugin.worldService.getWorlds() : null;

                                        java.util.List<java.util.Map<String,Object>> worldsOut = new java.util.ArrayList<>();
                                        if (wr != null && wr.getWorlds() != null) {
                                            for (net.runelite.http.api.worlds.World w : wr.getWorlds()) {
                                                if (w == null) continue;
                                                boolean members = false;
                                                try {
                                                    java.util.Set<net.runelite.http.api.worlds.WorldType> types = w.getTypes();
                                                    members = (types != null && types.contains(net.runelite.http.api.worlds.WorldType.MEMBERS));
                                                } catch (Throwable ignored) {}

                                                java.util.Map<String,Object> row = new java.util.LinkedHashMap<>();
                                                row.put("id", w.getId());
                                                row.put("members", members);
                                                try {
                                                    java.util.Set<net.runelite.http.api.worlds.WorldType> types = w.getTypes();
                                                    if (types != null) {
                                                        java.util.List<String> ts = new java.util.ArrayList<>();
                                                        for (net.runelite.http.api.worlds.WorldType t : types) if (t != null) ts.add(t.name());
                                                        row.put("types", ts);
                                                    } else {
                                                        row.put("types", java.util.Collections.emptyList());
                                                    }
                                                } catch (Throwable ignored) {
                                                    row.put("types", java.util.Collections.emptyList());
                                                }

                                                worldsOut.add(row);
                                            }
                                        }

                                        resp.put("ok", true);
                                        resp.put("count", worldsOut.size());
                                        resp.put("worlds", worldsOut);
                                    } catch (Throwable t) {
                                        resp.put("ok", false);
                                        resp.put("err", "get-worlds-failed");
                                        resp.put("exception", t.getMessage());
                                    } finally {
                                        fut.complete(resp);
                                    }
                                });

                                java.util.Map<String,Object> result;
                                try {
                                    result = fut.get(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                                } catch (Exception e) {
                                    result = java.util.Map.of("ok", false, "err", "timeout");
                                }
                                out.println(gson.toJson(result));
                                break;
                            }

                            case "hop_world": {
                                final java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
                                try {
                                    Integer targetWorldId = cmd.world_id;
                                    if (targetWorldId == null) {
                                        resp.put("ok", false);
                                        resp.put("err", "world_id required");
                                    } else {
                                        // Find the world in the world list
                                        net.runelite.api.World[] worldArray = client.getWorldList();
                                        net.runelite.api.World targetWorld = null;
                                        for (int i = 0; i < worldArray.length; i++) {
                                            net.runelite.api.World world = worldArray[i];
                                            if (world.getId() == targetWorldId) {
                                                targetWorld = world;
                                                break;
                                            }
                                        }
                                        
                                        if (targetWorld != null) {
                                            client.hopToWorld(targetWorld);
                                            resp.put("ok", true);
                                            resp.put("message", "Hopped to world " + targetWorldId);
                                        } else {
                                            resp.put("ok", false);
                                            resp.put("err", "World " + targetWorldId + " not found");
                                        }
                                    }
                                } catch (Throwable t) {
                                    resp.put("ok", false);
                                    resp.put("err", "hop-world-failed");
                                    resp.put("exception", t.getMessage());
                                }
                                out.println(gson.toJson(resp));
                                break;
                            }

                            case "openWorldHopper": {
                                final java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
                                try {
                                    client.openWorldHopper();
                                    resp.put("ok", true);
                                    resp.put("message", "World hopper opened");
                                } catch (Throwable t) {
                                    resp.put("ok", false);
                                    resp.put("err", "open-world-hopper-failed");
                                    resp.put("exception", t.getMessage());
                                }
                                out.println(gson.toJson(resp));
                                break;
                            }

                            case "where": {
                                final Component c = (Component) client.getCanvas();
                                if (c == null)
                                {
                                    out.println("{\"ok\":false,\"err\":\"no-canvas\"}");
                                    break;
                                }
                                try
                                {
                                    final java.awt.Point p = c.getLocationOnScreen();
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
                                final boolean hoverOnly = Boolean.TRUE.equals(cmd.hoverOnly);
                                final int hoverDelay = Math.max(0, config.hoverDelayMs());

                                final boolean onLogin =
                                        client.getGameState() == GameState.LOGIN_SCREEN ||
                                                client.getGameState() == GameState.LOGIN_SCREEN_AUTHENTICATOR;

                                // Use CompletableFuture to capture tile after click
                                final java.util.concurrent.CompletableFuture<java.util.Map<String,Object>> fut =
                                        new java.util.concurrent.CompletableFuture<>();

                                javax.swing.SwingUtilities.invokeLater(() -> {
                                    try {
                                        java.awt.Component comp = (java.awt.Component) client.getCanvas();
                                        if (comp == null) {
                                            fut.complete(java.util.Map.of("ok", false, "err", "no-canvas"));
                                            return;
                                        }

                                        // Login screen handling
                                        if (onLogin) {
                                            // Bounds guard (canvas-local coords)
                                            if (cx < 0 || cy < 0 || cx >= comp.getWidth() || cy >= comp.getHeight()) {
                                                log.info("login-click OOB: canvas={}x{} target=({}, {})",
                                                        comp.getWidth(), comp.getHeight(), cx, cy);
                                                fut.complete(java.util.Map.of("ok", false, "err", "out-of-bounds"));
                                                return;
                                            }

                                            // --- LOGIN: post events via AWT System EventQueue (don't dispatch directly) ---
                                            final java.awt.EventQueue q = java.awt.Toolkit.getDefaultToolkit().getSystemEventQueue();
                                            final long t0 = System.currentTimeMillis();

                                            // Move
                                            q.postEvent(new java.awt.event.MouseEvent(
                                                    comp, java.awt.event.MouseEvent.MOUSE_MOVED, t0, 0,
                                                    cx, cy, 0, false, java.awt.event.MouseEvent.NOBUTTON));

                                            if (!hoverOnly) {
                                                final int awtButton = (btn == 3) ? java.awt.event.MouseEvent.BUTTON3
                                                        : (btn == 2) ? java.awt.event.MouseEvent.BUTTON2
                                                        : java.awt.event.MouseEvent.BUTTON1;
                                                final int modMask = (btn == 3) ? java.awt.event.InputEvent.BUTTON3_DOWN_MASK
                                                        : (btn == 2) ? java.awt.event.InputEvent.BUTTON2_DOWN_MASK
                                                        : java.awt.event.InputEvent.BUTTON1_DOWN_MASK;

                                                try { Thread.sleep(hoverDelay); } catch (InterruptedException ignored) {}

                                                // Press
                                                q.postEvent(new java.awt.event.MouseEvent(
                                                        comp, java.awt.event.MouseEvent.MOUSE_PRESSED, t0 + 1, modMask,
                                                        cx, cy, 1, false, awtButton));
                                                try { Thread.sleep(5); } catch (InterruptedException ignored) {}

                                                // Release
                                                q.postEvent(new java.awt.event.MouseEvent(
                                                        comp, java.awt.event.MouseEvent.MOUSE_RELEASED, t0 + 6, modMask,
                                                        cx, cy, 1, false, awtButton));

                                                // Click (no mask)
                                                q.postEvent(new java.awt.event.MouseEvent(
                                                        comp, java.awt.event.MouseEvent.MOUSE_CLICKED, t0 + 11, 0,
                                                        cx, cy, 1, false, awtButton));
                                            }

                                            fut.complete(java.util.Map.of("ok", true, "mode", "AWT_LOGIN", "hoverDelayMs", hoverDelay));
                                            return; // login handled; skip in-game path
                                        }

                                        // --- IN-GAME: keep your existing dispatch pattern (works fine for you) ---
                                        long now = System.currentTimeMillis();
                                        comp.dispatchEvent(new java.awt.event.MouseEvent(
                                                comp, java.awt.event.MouseEvent.MOUSE_MOVED, now, 0, cx, cy, 0, false));

                                        if (!hoverOnly) {
                                            final int awtButton = (btn == 3) ? java.awt.event.MouseEvent.BUTTON3
                                                    : (btn == 2) ? java.awt.event.MouseEvent.BUTTON2
                                                    : java.awt.event.MouseEvent.BUTTON1;
                                            final int modMask = (btn == 3) ? java.awt.event.InputEvent.BUTTON3_DOWN_MASK
                                                    : (btn == 2) ? java.awt.event.InputEvent.BUTTON2_DOWN_MASK
                                                    : java.awt.event.InputEvent.BUTTON1_DOWN_MASK;

                                            javax.swing.Timer t = new javax.swing.Timer(hoverDelay, ev -> {
                                                long t0 = System.currentTimeMillis();
                                                // Press (use mask so listeners see the button)
                                                comp.dispatchEvent(new java.awt.event.MouseEvent(
                                                        comp, java.awt.event.MouseEvent.MOUSE_PRESSED,  t0,   modMask, cx, cy, 1, false, awtButton));
                                                try { Thread.sleep(5); } catch (InterruptedException ignored) {}
                                                comp.dispatchEvent(new java.awt.event.MouseEvent(
                                                        comp, java.awt.event.MouseEvent.MOUSE_RELEASED, t0+5, modMask, cx, cy, 1, false, awtButton));
                                                comp.dispatchEvent(new java.awt.event.MouseEvent(
                                                        comp, java.awt.event.MouseEvent.MOUSE_CLICKED,  t0+10, 0,      cx, cy, 1, false, awtButton));
                                                
                                                // After click completes, wait a bit and capture the tile
                                                javax.swing.Timer tileTimer = new javax.swing.Timer(50, ev2 -> {
                                                    clientThread.invokeLater(() -> {
                                                        java.util.Map<String, Object> resp = new java.util.LinkedHashMap<>();
                                                        resp.put("ok", true);
                                                        resp.put("mode", "AWT");
                                                        resp.put("hoverDelayMs", hoverDelay);
                                                        
                                                        // Capture the clicked tile
                                                        Tile clickedTile = client.getSelectedSceneTile();
                                                        if (clickedTile != null) {
                                                            WorldPoint worldPos = clickedTile.getWorldLocation();
                                                            if (worldPos != null) {
                                                                java.util.Map<String, Object> tileData = new java.util.LinkedHashMap<>();
                                                                tileData.put("x", worldPos.getX());
                                                                tileData.put("y", worldPos.getY());
                                                                tileData.put("plane", worldPos.getPlane());
                                                                resp.put("tile", tileData);
                                                            } else {
                                                                resp.put("tile", null);
                                                            }
                                                        } else {
                                                            resp.put("tile", null);
                                                        }
                                                        
                                                        fut.complete(resp);
                                                    });
                                                });
                                                tileTimer.setRepeats(false);
                                                tileTimer.start();
                                            });
                                            t.setRepeats(false);
                                            t.start();
                                        } else {
                                            // Hover only - still capture tile
                                            javax.swing.Timer tileTimer = new javax.swing.Timer(50, ev -> {
                                                clientThread.invokeLater(() -> {
                                                    java.util.Map<String, Object> resp = new java.util.LinkedHashMap<>();
                                                    resp.put("ok", true);
                                                    resp.put("mode", "AWT");
                                                    resp.put("hoverDelayMs", hoverDelay);
                                                    
                                                    Tile clickedTile = client.getSelectedSceneTile();
                                                    if (clickedTile != null) {
                                                        WorldPoint worldPos = clickedTile.getWorldLocation();
                                                        if (worldPos != null) {
                                                            java.util.Map<String, Object> tileData = new java.util.LinkedHashMap<>();
                                                            tileData.put("x", worldPos.getX());
                                                            tileData.put("y", worldPos.getY());
                                                            tileData.put("plane", worldPos.getPlane());
                                                            resp.put("tile", tileData);
                                                        } else {
                                                            resp.put("tile", null);
                                                        }
                                                    } else {
                                                        resp.put("tile", null);
                                                    }
                                                    
                                                    fut.complete(resp);
                                                });
                                            });
                                            tileTimer.setRepeats(false);
                                            tileTimer.start();
                                        }
                                    } catch (Exception e) {
                                        log.warn("click handler failed: {}", e.toString());
                                        fut.complete(java.util.Map.of("ok", false, "err", e.toString()));
                                    }
                                });
                                
                                // Wait for result and send response
                                java.util.Map<String,Object> result;
                                try {
                                    result = fut.get(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                                } catch (Exception e) {
                                    result = java.util.Map.of("ok", false, "err", "timeout");
                                }
                                out.println(gson.toJson(result));
                                break;
                            }

                            case "get_last_interaction": {
                                final java.util.concurrent.CompletableFuture<java.util.Map<String,Object>> fut =
                                        new java.util.concurrent.CompletableFuture<>();
                                
                                clientThread.invokeLater(() -> {
                                    final java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
                                    try {
                                        // Get the last interaction data directly from this plugin
                                        java.util.Map<String, Object> lastInteraction = plugin.getLastInteraction();
                                        if (lastInteraction != null) {
                                            resp.put("ok", true);
                                            resp.put("interaction", lastInteraction);
                                        } else {
                                            resp.put("ok", true);
                                            resp.put("interaction", null);
                                        }
                                        
                                    } catch (Throwable t) {
                                        resp.put("ok", false);
                                        resp.put("err", "get-last-interaction-failed");
                                    } finally {
                                        fut.complete(resp);
                                    }
                                });
                                
                                java.util.Map<String,Object> result;
                                try {
                                    result = fut.get(150, java.util.concurrent.TimeUnit.MILLISECONDS);
                                } catch (Exception e) {
                                    result = java.util.Map.of("ok", false, "err", "timeout");
                                }
                                out.println(gson.toJson(result));
                                break;
                            }

                            case "get_selected_tile": {
                                final java.util.concurrent.CompletableFuture<java.util.Map<String,Object>> fut =
                                        new java.util.concurrent.CompletableFuture<>();
                                
                                clientThread.invokeLater(() -> {
                                    final java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
                                    try {
                                        // Get the currently selected scene tile from the game
                                        Tile selectedTile = client.getSelectedSceneTile();
                                        if (selectedTile != null) {
                                            WorldPoint worldPos = selectedTile.getWorldLocation();
                                            if (worldPos != null) {
                                                java.util.Map<String, Object> tileData = new java.util.LinkedHashMap<>();
                                                tileData.put("x", worldPos.getX());
                                                tileData.put("y", worldPos.getY());
                                                tileData.put("plane", worldPos.getPlane());
                                                resp.put("ok", true);
                                                resp.put("tile", tileData);
                                            } else {
                                                resp.put("ok", true);
                                                resp.put("tile", null);
                                            }
                                        } else {
                                            resp.put("ok", true);
                                            resp.put("tile", null);
                                        }
                                        
                                    } catch (Throwable t) {
                                        resp.put("ok", false);
                                        resp.put("err", "get-selected-tile-failed");
                                    } finally {
                                        fut.complete(resp);
                                    }
                                });
                                
                                java.util.Map<String,Object> result;
                                try {
                                    result = fut.get(150, java.util.concurrent.TimeUnit.MILLISECONDS);
                                } catch (Exception e) {
                                    result = java.util.Map.of("ok", false, "err", "timeout");
                                }
                                out.println(gson.toJson(result));
                                break;
                            }

                            case "get_players": {
                                final java.util.concurrent.CompletableFuture<java.util.Map<String,Object>> fut =
                                        new java.util.concurrent.CompletableFuture<>();
                                
                                clientThread.invokeLater(() -> {
                                    final java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
                                    try {
                                        java.util.List<java.util.Map<String,Object>> players = new java.util.ArrayList<>();
                                        
                                        // Get all players in the scene
                                        java.util.List<Player> allPlayers = client.getPlayers();
                                        if (allPlayers != null) {
                                            for (Player player : allPlayers) {
                                                if (player != null) {
                                                    java.util.Map<String,Object> playerData = new java.util.LinkedHashMap<>();
                                                    
                                                    // Basic player info
                                                    playerData.put("name", player.getName());
                                                    playerData.put("combatLevel", player.getCombatLevel());
                                                    playerData.put("isLocalPlayer", player == client.getLocalPlayer());
                                                    
                                                    // World location
                                                    WorldPoint worldLocation = player.getWorldLocation();
                                                    if (worldLocation != null) {
                                                        playerData.put("worldX", worldLocation.getX());
                                                        playerData.put("worldY", worldLocation.getY());
                                                        playerData.put("plane", worldLocation.getPlane());
                                                    }
                                                    
                                                    // Canvas location
                                                    java.awt.Polygon canvasTilePoly = player.getCanvasTilePoly();
                                                    if (canvasTilePoly != null) {
                                                        playerData.put("canvasX", canvasTilePoly.getBounds().x + canvasTilePoly.getBounds().width / 2);
                                                        playerData.put("canvasY", canvasTilePoly.getBounds().y + canvasTilePoly.getBounds().height / 2);
                                                    }
                                                    
                                                    // Bounds
                                                    java.awt.Rectangle bounds = player.getCanvasTilePoly().getBounds();
                                                    if (bounds != null) {
                                                        playerData.put("bounds", java.util.Map.of(
                                                            "x", bounds.x,
                                                            "y", bounds.y,
                                                            "width", bounds.width,
                                                            "height", bounds.height
                                                        ));
                                                    }
                                                    
                                                    // Animation
                                                    playerData.put("animation", player.getAnimation());
                                                    
                                                    // Orientation (facing direction in degrees, 0-2047)
                                                    playerData.put("orientation", player.getOrientation());
                                                    
                                                    // Health info
                                                    playerData.put("healthRatio", player.getHealthRatio());
                                                    playerData.put("healthScale", player.getHealthScale());
                                                    
                                                    // Skull icon
                                                    playerData.put("skullIcon", player.getSkullIcon());
                                                    
                                                    // Overhead icon
                                                    playerData.put("overheadIcon", player.getOverheadIcon());
                                                    
                                                    players.add(playerData);
                                                }
                                            }
                                        }
                                        
                                        resp.put("ok", true);
                                        resp.put("players", players);
                                        resp.put("count", players.size());
                                        
                                    } catch (Throwable t) {
                                        resp.put("ok", false);
                                        resp.put("err", "get-players-failed");
                                    } finally {
                                        fut.complete(resp);
                                    }
                                });
                                
                                java.util.Map<String,Object> result;
                                try {
                                    result = fut.get(150, java.util.concurrent.TimeUnit.MILLISECONDS);
                                } catch (Exception e) {
                                    result = java.util.Map.of("ok", false, "err", "timeout");
                                }
                                out.println(gson.toJson(result));
                                break;
                            }

                            case "get-var": {
                                if (cmd.id == null) { out.println("{\"ok\":false,\"err\":\"need id\"}"); break; }

                                final java.util.concurrent.CompletableFuture<java.util.Map<String,Object>> fut =
                                        new java.util.concurrent.CompletableFuture<>();

                                clientThread.invokeLater(() -> {
                                    final java.util.Map<String,Object> r = new java.util.LinkedHashMap<>();
                                    try {
                                        int value = client.getVarbitValue(cmd.id);
                                        r.put("ok", true);
                                        r.put("id", cmd.id);
                                        r.put("value", value);
                                    } catch (Throwable t) {
                                        r.put("ok", false);
                                        r.put("err", "get-var-failed");
                                    } finally {
                                        fut.complete(r);
                                    }
                                });

                                java.util.Map<String,Object> resp;
                                try {
                                    resp = fut.get(150, java.util.concurrent.TimeUnit.MILLISECONDS);
                                } catch (Exception e) {
                                    resp = java.util.Map.of("ok", false, "err", "timeout");
                                }
                                out.println(gson.toJson(resp));
                                break;
                            }


                            case "menu": {
                                // Snapshot on client thread so geometry & entries are consistent
                                final java.util.concurrent.CompletableFuture<java.util.Map<String,Object>> fut =
                                        new java.util.concurrent.CompletableFuture<>();

                                clientThread.invokeLater(() -> {
                                    final java.util.Map<String,Object> r = new java.util.LinkedHashMap<>();
                                    try {
                                        int x = client.getMenuX();
                                        int y = client.getMenuY();
                                        int w = client.getMenuWidth();
                                        int h = client.getMenuHeight();
                                        net.runelite.api.MenuEntry[] es = client.getMenuEntries();

                                        boolean open = w > 0 && h > 0 && es != null && es.length > 0;
                                        r.put("ok", true);
                                        r.put("open", open);
                                        r.put("x", x);
                                        r.put("y", y);
                                        r.put("w", w);
                                        r.put("h", h);

                                        // Heuristic: header ~19px; rowH already computed
                                        final int HEADER = 19;
                                        int rowH = !open ? 0 : Math.max(12, (h - HEADER) / es.length);

                                        java.util.List<java.util.Map<String,Object>> outMenu = new java.util.ArrayList<>();
                                        if (open) {
                                            for (int i = 0; i < es.length; i++) {
                                                net.runelite.api.MenuEntry me = es[i];

                                                // entries[] is bottom->top; UI draws top->bottom
                                                final int visualIndex = (es.length - 1 - i); // 0 = top visible row
                                                final int rx = x;
                                                final int ry = y + HEADER + visualIndex * rowH;

                                                java.util.Map<String,Object> row = new java.util.LinkedHashMap<>();
                                                row.put("index", i);               // array index (bottom->top)
                                                row.put("visualIndex", visualIndex); // drawn row (top->bottom)
                                                row.put("option", me.getOption());
                                                row.put("target", me.getTarget());
                                                row.put("type", String.valueOf(me.getType()));
                                                row.put("identifier", me.getIdentifier());
                                                row.put("rect", java.util.Map.of("x", rx, "y", ry, "w", w, "h", rowH));

                                                outMenu.add(row);
                                            }
                                        }
                                        r.put("entries", outMenu);
                                    } catch (Throwable t) {
                                        r.clear();
                                        r.put("ok", false);
                                        r.put("err", "menu-snapshot-failed");
                                    } finally {
                                        fut.complete(r);
                                    }
                                });

                                java.util.Map<String,Object> resp;
                                try { resp = fut.get(120, java.util.concurrent.TimeUnit.MILLISECONDS); }
                                catch (Exception e) { resp = java.util.Map.of("ok", false, "err", "timeout"); }
                                out.println(gson.toJson(resp));
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

                                out.println("{\"ok\":true,\"mode\":\""+mode+"\",\"key\":\""+key+"\",\"ms\":"+ms+"}");
                                break;
                            }

                            case "keyPress": {
                                final String key = (cmd.key == null) ? "" : String.valueOf(cmd.key);
                                final String mode = config.mode().trim().toUpperCase();

                                // AWT dispatch to the RuneLite canvas (works while window is showing, even if not OS-active)
                                new Thread(() -> {
                                    try {
                                        final java.awt.Component c = (java.awt.Component) client.getCanvas();
                                        if (c == null) return;
                                        final int vk = keyCodeFrom(key);
                                        if (vk < 0) { log.warn("[IPC] keyPress unknown key '{}'", key); return; }

                                        final long t0 = System.currentTimeMillis();
                                        java.awt.EventQueue.invokeAndWait(() -> {
                                            c.dispatchEvent(new java.awt.event.KeyEvent(
                                                    c, java.awt.event.KeyEvent.KEY_PRESSED, t0, 0, vk,
                                                    java.awt.event.KeyEvent.CHAR_UNDEFINED));
                                        });
                                    } catch (Exception e) {
                                        log.warn("[IPC] keyPress AWT failed: {}", e.toString());
                                    }
                                }, "ipc-keypress-awt").start();

                                out.println("{\"ok\":true,\"mode\":\""+mode+"\",\"key\":\""+key+"\"}");
                                break;
                            }

                            case "keyRelease": {
                                final String key = (cmd.key == null) ? "" : String.valueOf(cmd.key);
                                final String mode = config.mode().trim().toUpperCase();

                                // AWT dispatch to the RuneLite canvas (works while window is showing, even if not OS-active)
                                new Thread(() -> {
                                    try {
                                        final java.awt.Component c = (java.awt.Component) client.getCanvas();
                                        if (c == null) return;
                                        final int vk = keyCodeFrom(key);
                                        if (vk < 0) { log.warn("[IPC] keyRelease unknown key '{}'", key); return; }

                                        final long t0 = System.currentTimeMillis();
                                        java.awt.EventQueue.invokeAndWait(() -> {
                                            c.dispatchEvent(new java.awt.event.KeyEvent(
                                                    c, java.awt.event.KeyEvent.KEY_RELEASED, t0, 0, vk,
                                                    java.awt.event.KeyEvent.CHAR_UNDEFINED));
                                        });
                                    } catch (Exception e) {
                                        log.warn("[IPC] keyRelease AWT failed: {}", e.toString());
                                    }
                                }, "ipc-keyrelease-awt").start();

                                out.println("{\"ok\":true,\"mode\":\""+mode+"\",\"key\":\""+key+"\"}");
                                break;
                            }


                            case "type": {
                                final String text = (cmd.text == null) ? "" : cmd.text;
                                final int perCharMs = (cmd.perCharMs == null) ? 25 : Math.max(0, cmd.perCharMs);
                                final String mode = config.mode().trim().toUpperCase();

                                if (text.isEmpty()) { out.println("{\"ok\":false,\"err\":\"need text\"}"); break; }

                                out.println("{\"ok\":true,\"mode\":\"" + mode + "\",\"accepted\":\"type\"}");

                                new Thread(() -> {
                                    try {
                                        typeStringAWT(client, text, perCharMs);
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

                            case "objects": {
                                final String needle = (cmd.name == null ? "" : cmd.name.trim().toLowerCase());
                                final int radius = (cmd.radius == null) ? 26 : Math.max(1, cmd.radius);

                                // which object kinds to include
                                final java.util.Set<String> kinds = new java.util.HashSet<>();
                                if (cmd.types != null) {
                                    for (String t : cmd.types) if (t != null) kinds.add(t.trim().toUpperCase());
                                }
                                if (kinds.isEmpty()) {
                                    kinds.add("GAME");
                                    kinds.add("WALL");
                                    kinds.add("DECOR");
                                    kinds.add("GROUND");
                                }

                                final java.util.concurrent.CompletableFuture<java.util.List<java.util.Map<String,Object>>> fut =
                                        new java.util.concurrent.CompletableFuture<>();

                                clientThread.invokeLater(() -> {
                                    final java.util.List<java.util.Map<String,Object>> objsOut = new java.util.ArrayList<>();
                                    try {
                                        final Player me = client.getLocalPlayer();
                                        final Scene scene = client.getScene();
                                        if (me == null || scene == null) { fut.complete(objsOut); return; }

                                        final int plane = client.getPlane();
                                        final int baseX = client.getBaseX(), baseY = client.getBaseY();
                                        final int myWx = me.getWorldLocation().getX();
                                        final int myWy = me.getWorldLocation().getY();
                                        final int minWx = myWx - radius, maxWx = myWx + radius;
                                        final int minWy = myWy - radius, maxWy = myWy + radius;

                                        final Tile[][][] tiles = scene.getTiles();
                                        if (tiles == null || plane < 0 || plane >= tiles.length || tiles[plane] == null) {
                                            fut.complete(objsOut); return;
                                        }

                                        // helper: add one RuneLite object to the list if it matches
                                        java.util.function.BiConsumer<String, TileObject> addObj = (kind, obj) -> {
                                            if (obj == null) return;
                                            final int id = obj.getId();
                                            final ObjectComposition baseComp = client.getObjectDefinition(id);
                                            final ObjectComposition comp = resolveObjectComposition(client, baseComp);
                                            String nm = (comp != null && comp.getName() != null) ? comp.getName() : "";
                                            String[] actions = (comp != null) ? comp.getActions() : null;
                                            
                                            final String nmLower = nm.toLowerCase();
                                            if (!needle.isEmpty() && !nmLower.equals(needle)) return;

                                            // world coords from local
                                            final int wx = baseX + obj.getLocalLocation().getSceneX();
                                            final int wy = baseY + obj.getLocalLocation().getSceneY();
                                            if (wx < minWx || wx > maxWx || wy < minWy || wy > maxWy) return;

                                            final java.util.Map<String,Object> row = new java.util.LinkedHashMap<>();
                                            row.put("type", kind);
                                            row.put("id", id);
                                            row.put("name", nm);
                                            row.put("actions", actions);
                                            row.put("world", java.util.Map.of("x", wx, "y", wy, "p", plane));
                                            row.put("distance", Math.abs(wx - myWx) + Math.abs(wy - myWy));
                                            row.put("plane", plane);

                                            // Get modelHeight and vertexHeight from Renderable (only for GameObject)
                                            try {
                                                if (obj instanceof net.runelite.api.GameObject) {
                                                    final net.runelite.api.GameObject go = (net.runelite.api.GameObject)obj;
                                                    final Renderable rend = go.getRenderable();
                                                    if (rend != null) {
                                                        final int modelHeight = rend.getModelHeight();
                                                        row.put("modelHeight", modelHeight);
                                                        
                                                        // Calculate vertexHeight from model vertices
                                                        final Model m = rend.getModel();
                                                        if (m != null) {
                                                            final float[] ys = m.getVerticesY();
                                                            if (ys != null && ys.length > 0) {
                                                                float min = ys[0], max = ys[0];
                                                                for (float y : ys) {
                                                                    if (y < min) min = y;
                                                                    if (y > max) max = y;
                                                                }
                                                                final float vertexHeight = max - min;
                                                                row.put("vertexHeight", vertexHeight);
                                                            }
                                                        }
                                                    }
                                                }
                                            } catch (Exception ignored) {}

                                            // convex hull bounds + orientation + canvas fallback
                                            try {
                                                final java.awt.Shape hull =
                                                        (obj instanceof net.runelite.api.GameObject) ? ((net.runelite.api.GameObject)obj).getConvexHull()
                                                                : (obj instanceof net.runelite.api.DecorativeObject) ? ((net.runelite.api.DecorativeObject)obj).getConvexHull()
                                                                : (obj instanceof net.runelite.api.WallObject) ? ((net.runelite.api.WallObject)obj).getConvexHull()
                                                                : (obj instanceof net.runelite.api.GroundObject) ? ((net.runelite.api.GroundObject)obj).getConvexHull()
                                                                : null;
                                                if (hull != null) {
                                                    final java.awt.Rectangle rb = hull.getBounds();
                                                    if (rb != null) {
                                                        row.put("bounds", java.util.Map.of(
                                                                "x", rb.x, "y", rb.y, "width", rb.width, "height", rb.height));
                                                        row.put("canvas", java.util.Map.of(
                                                                "x", rb.x + rb.width/2, "y", rb.y + rb.height/2));
                                                        row.put("orientation", (rb.width > rb.height) ? "horizontal" : "vertical");
                                                    }
                                                }
                                            } catch (Exception ignored) {}

                                            try {
                                                final LocalPoint lp = LocalPoint.fromWorld(client, wx, wy);
                                                if (lp != null) {
                                                    final net.runelite.api.Point pt = Perspective.localToCanvas(client, lp, plane);
                                                    if (pt != null) row.put("tileCanvas", java.util.Map.of("x", pt.getX(), "y", pt.getY()));
                                                }
                                            } catch (Exception ignored) {}

                                            objsOut.add(row);
                                        };

                                        // iterate the scene within our radius window
                                        final int minLx = Math.max(0, minWx - baseX), maxLx = Math.min(103, maxWx - baseX);
                                        final int minLy = Math.max(0, minWy - baseY), maxLy = Math.min(103, maxWy - baseY);
                                        for (int lx = minLx; lx <= maxLx; lx++) {
                                            for (int ly = minLy; ly <= maxLy; ly++) {
                                                final Tile t = tiles[plane][lx][ly];
                                                if (t == null) continue;

                                                if (kinds.contains("WALL"))  addObj.accept("WALL",  t.getWallObject());
                                                if (kinds.contains("DECOR")) addObj.accept("DECOR", t.getDecorativeObject());
                                                if (kinds.contains("GROUND")) addObj.accept("GROUND", t.getGroundObject());
                                                if (kinds.contains("GAME")) {
                                                    final GameObject[] arr = t.getGameObjects();
                                                    if (arr != null) for (GameObject go : arr) addObj.accept("GAME", go);
                                                }
                                            }
                                        }

                                        // sort by distance, then name
                                        objsOut.sort((a,b) -> {
                                            int da = ((Number)a.getOrDefault("distance", 1_000_000)).intValue();
                                            int db = ((Number)b.getOrDefault("distance", 1_000_000)).intValue();
                                            if (da != db) return Integer.compare(da, db);
                                            String na = String.valueOf(a.getOrDefault("name",""));
                                            String nb = String.valueOf(b.getOrDefault("name",""));
                                            return na.compareToIgnoreCase(nb);
                                        });

                                    } catch (Throwable ignored) {
                                    } finally {
                                        fut.complete(objsOut);
                                    }
                                });

                                java.util.List<java.util.Map<String,Object>> found;
                                try {
                                    found = fut.get(150, java.util.concurrent.TimeUnit.MILLISECONDS);
                                } catch (Exception e) {
                                    found = java.util.Collections.emptyList();
                                }

                                java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
                                resp.put("ok", true);
                                resp.put("count", found.size());
                                resp.put("objects", found);
                                out.println(gson.toJson(resp)); // <-- this 'out' is the PrintWriter; now no collision
                                break;
                            }

                            case "npcs": {
                                final String needle = (cmd.name == null ? "" : cmd.name.trim().toLowerCase());
                                final int radius = (cmd.radius == null) ? 26 : Math.max(1, cmd.radius);

                                final java.util.concurrent.CompletableFuture<java.util.List<java.util.Map<String,Object>>> fut =
                                        new java.util.concurrent.CompletableFuture<>();

                                clientThread.invokeLater(() -> {
                                    final java.util.List<java.util.Map<String,Object>> npcsOut = new java.util.ArrayList<>();
                                    try {
                                        final Player me = client.getLocalPlayer();
                                        final Scene scene = client.getScene();
                                        if (me == null || scene == null) { fut.complete(npcsOut); return; }

                                        final int plane = client.getPlane();
                                        final int baseX = client.getBaseX(), baseY = client.getBaseY();
                                        final int myWx = me.getWorldLocation().getX();
                                        final int myWy = me.getWorldLocation().getY();
                                        final int minWx = myWx - radius, maxWx = myWx + radius;
                                        final int minWy = myWy - radius, maxWy = myWy + radius;

                                        // Get all NPCs from the client
                                        final java.util.List<NPC> npcs = client.getNpcs();
                                        if (npcs == null) { fut.complete(npcsOut); return; }

                                        for (NPC npc : npcs) {
                                            if (npc == null) continue;

                                            // Get NPC composition for name and actions
                                            final NPCComposition comp = client.getNpcDefinition(npc.getId());
                                            final String nm = (comp != null && comp.getName() != null) ? comp.getName() : "";
                                            final String nmLower = nm.toLowerCase();
                                            if (!needle.isEmpty() && !nmLower.contains(needle)) continue;

                                            // Get world coordinates
                                            final int wx = npc.getWorldLocation().getX();
                                            final int wy = npc.getWorldLocation().getY();
                                            if (wx < minWx || wx > maxWx || wy < minWy || wy > maxWy) continue;

                                            final java.util.Map<String,Object> row = new java.util.LinkedHashMap<>();
                                            row.put("type", "NPC");
                                            row.put("id", npc.getId());
                                            row.put("name", nm);
                                            row.put("actions", (comp != null) ? comp.getActions() : null);
                                            row.put("world", java.util.Map.of("x", wx, "y", wy, "p", plane));
                                            row.put("distance", Math.abs(wx - myWx) + Math.abs(wy - myWy));
                                            
                                            // Health info (only known when a health bar is active; otherwise -1/-1)
                                            try {
                                                row.put("healthRatio", npc.getHealthRatio());
                                                row.put("healthScale", npc.getHealthScale());
                                            } catch (Throwable ignored) {
                                                row.put("healthRatio", -1);
                                                row.put("healthScale", -1);
                                            }
                                            
                                            // Add combat status
                                            Actor interacting = npc.getInteracting();
                                            boolean inCombat = (interacting != null);
                                            row.put("inCombat", inCombat);
                                            if (inCombat) {
                                                row.put("combatTarget", interacting.getName());
                                                row.put("combatTargetType", interacting instanceof Player ? "Player" : "NPC");
                                            }

                                            // Get canvas position from convex hull if available
                                            try {
                                                final java.awt.Shape hull = npc.getConvexHull();
                                                if (hull != null) {
                                                    final java.awt.Rectangle rb = hull.getBounds();
                                                    if (rb != null) {
                                                        row.put("bounds", java.util.Map.of(
                                                                "x", rb.x, "y", rb.y, "width", rb.width, "height", rb.height));
                                                        row.put("canvas", java.util.Map.of(
                                                                "x", rb.x + rb.width/2, "y", rb.y + rb.height/2));
                                                        row.put("orientation", (rb.width > rb.height) ? "horizontal" : "vertical");
                                                    }
                                                }
                                            } catch (Exception ignored) {}

                                            // Fallback: tile center projection
                                            try {
                                                final LocalPoint lp = LocalPoint.fromWorld(client, wx, wy);
                                                if (lp != null) {
                                                    final net.runelite.api.Point pt = Perspective.localToCanvas(client, lp, plane);
                                                    if (pt != null) row.put("tileCanvas", java.util.Map.of("x", pt.getX(), "y", pt.getY()));
                                                }
                                            } catch (Exception ignored) {}

                                            npcsOut.add(row);
                                        }

                                        // Sort by distance, then name
                                        npcsOut.sort((a,b) -> {
                                            int da = ((Number)a.getOrDefault("distance", 1_000_000)).intValue();
                                            int db = ((Number)b.getOrDefault("distance", 1_000_000)).intValue();
                                            if (da != db) return Integer.compare(da, db);
                                            String na = String.valueOf(a.getOrDefault("name",""));
                                            String nb = String.valueOf(b.getOrDefault("name",""));
                                            return na.compareToIgnoreCase(nb);
                                        });

                                    } catch (Throwable ignored) {
                                    } finally {
                                        fut.complete(npcsOut);
                                    }
                                });

                                java.util.List<java.util.Map<String,Object>> found;
                                try {
                                    found = fut.get(150, java.util.concurrent.TimeUnit.MILLISECONDS);
                                } catch (Exception e) {
                                    found = java.util.Collections.emptyList();
                                }

                                java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
                                resp.put("ok", true);
                                resp.put("count", found.size());
                                resp.put("npcs", found);
                                out.println(gson.toJson(resp));
                                break;
                            }

                            case "find_object": {
                                final String needle = (cmd.name == null ? "" : cmd.name.trim().toLowerCase());
                                final int radius = (cmd.radius == null) ? 26 : Math.max(1, cmd.radius);
                                final String objectType = (cmd.types != null && !cmd.types.isEmpty()) ? cmd.types.get(0).trim().toUpperCase() : "GAME";
                                final boolean exactMatch = (cmd.exactMatch != null) ? cmd.exactMatch : false;

                                final java.util.concurrent.CompletableFuture<java.util.Map<String,Object>> fut =
                                        new java.util.concurrent.CompletableFuture<>();

                                clientThread.invokeLater(() -> {
                                    final java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
                                    try {
                                        final Player me = client.getLocalPlayer();
                                        final Scene scene = client.getScene();
                                        if (me == null || scene == null) { 
                                            resp.put("ok", false); resp.put("err", "no-player-or-scene"); 
                                            fut.complete(resp); return; 
                                        }

                                        final int plane = client.getPlane();
                                        final int baseX = client.getBaseX(), baseY = client.getBaseY();
                                        final int myWx = me.getWorldLocation().getX();
                                        final int myWy = me.getWorldLocation().getY();
                                        final int minWx = myWx - radius, maxWx = myWx + radius;
                                        final int minWy = myWy - radius, maxWy = myWy + radius;

                                        final Tile[][][] tiles = scene.getTiles();
                                        if (tiles == null || plane < 0 || plane >= tiles.length || tiles[plane] == null) {
                                            resp.put("ok", false); resp.put("err", "no-tiles"); 
                                            fut.complete(resp); return;
                                        }

                                        // Helper to check if object matches name early (before expensive operations)
                                        java.util.function.Predicate<TileObject> nameMatches = (obj) -> {
                                            if (obj == null) return false;
                                            final int id = obj.getId();
                                            final ObjectComposition baseComp = client.getObjectDefinition(id);
                                            final ObjectComposition comp = resolveObjectComposition(client, baseComp);
                                            String nm = (comp != null && comp.getName() != null) ? comp.getName() : "";
                                            
                                            final String nmLower = nm.toLowerCase();
                                            if (needle.isEmpty()) return true;
                                            
                                            // Use exact match if specified
                                            if (exactMatch) {
                                                return nmLower.equals(needle);
                                            }
                                            
                                            // For "tree", be more specific - avoid gates, doors, etc.
                                            if ("tree".equals(needle)) {
                                                return nmLower.equals("tree") || 
                                                       nmLower.startsWith("tree ") || 
                                                       nmLower.endsWith(" tree") ||
                                                       (nmLower.contains("tree") && !nmLower.contains("gate") && !nmLower.contains("door"));
                                            }
                                            
                                            // For other objects, use normal matching
                                            return nmLower.equals(needle) || nmLower.contains(needle);
                                        };

                                        // Scan tiles and collect all matching objects, then pick the best one
                                        final int minLx = Math.max(0, minWx - baseX), maxLx = Math.min(103, maxWx - baseX);
                                        final int minLy = Math.max(0, minWy - baseY), maxLy = Math.min(103, maxWy - baseY);
                                        
                                        java.util.List<java.util.Map<String,Object>> candidates = new java.util.ArrayList<>();
                                        
                                        for (int lx = minLx; lx <= maxLx; lx++) {
                                            for (int ly = minLy; ly <= maxLy; ly++) {
                                                final Tile t = tiles[plane][lx][ly];
                                                if (t == null) continue;

                                                TileObject obj = null;
                                                if ("WALL".equals(objectType)) obj = t.getWallObject();
                                                else if ("DECOR".equals(objectType)) obj = t.getDecorativeObject();
                                                else if ("GROUND".equals(objectType)) obj = t.getGroundObject();
                                                else if ("GAME".equals(objectType)) {
                                                    final GameObject[] arr = t.getGameObjects();
                                                    if (arr != null) {
                                                        for (GameObject go : arr) {
                                                            if (go != null && nameMatches.test(go)) {
                                                                obj = go;
                                                                break;
                                                            }
                                                        }
                                                    }
                                                }

                                                if (obj != null && nameMatches.test(obj)) {
                                                    // Add to candidates list for later selection
                                                    final int id = obj.getId();
                                                    final ObjectComposition baseComp = client.getObjectDefinition(id);
                                                    final ObjectComposition comp = resolveObjectComposition(client, baseComp);
                                                    String nm = (comp != null && comp.getName() != null) ? comp.getName() : "";
                                                    String[] actions = (comp != null) ? comp.getActions() : null;

                                                    final int wx = baseX + obj.getLocalLocation().getSceneX();
                                                    final int wy = baseY + obj.getLocalLocation().getSceneY();
                                                    final int distance = Math.abs(wx - myWx) + Math.abs(wy - myWy);

                                                    final java.util.Map<String,Object> objData = new java.util.LinkedHashMap<>();
                                                    objData.put("id", id);
                                                    objData.put("type", objectType);
                                                    objData.put("name", nm);
                                                    objData.put("world", java.util.Map.of("x", wx, "y", wy, "p", plane));
                                                    objData.put("distance", distance);
                                                    objData.put("exactMatch", nm.toLowerCase().equals(needle));

                                                    if (actions != null) {
                                                        java.util.List<String> al = new java.util.ArrayList<>();
                                                        for (String a : actions) if (a != null) al.add(a);
                                                        objData.put("actions", al);
                                                    }

                                                    // Get bounds/canvas position
                                                    try {
                                                        final java.awt.Shape hull = (obj instanceof GameObject) ? ((GameObject)obj).getConvexHull()
                                                                : (obj instanceof DecorativeObject) ? ((DecorativeObject)obj).getConvexHull()
                                                                : (obj instanceof WallObject) ? ((WallObject)obj).getConvexHull()
                                                                : (obj instanceof GroundObject) ? ((GroundObject)obj).getConvexHull()
                                                                : null;
                                                        if (hull != null) {
                                                            final java.awt.Rectangle rb = hull.getBounds();
                                                            if (rb != null) {
                                                                objData.put("bounds", java.util.Map.of(
                                                                        "x", rb.x, "y", rb.y, "width", rb.width, "height", rb.height));
                                                                objData.put("canvas", java.util.Map.of(
                                                                        "x", rb.x + rb.width/2, "y", rb.y + rb.height/2));
                                                                objData.put("orientation", (rb.width > rb.height) ? "horizontal" : "vertical");
                                                            }
                                                        }
                                                    } catch (Exception ignored) {}

                                                    // Fallback: tile center projection
                                                    try {
                                                        final LocalPoint lp = LocalPoint.fromWorld(client, wx, wy);
                                                        if (lp != null) {
                                                            final net.runelite.api.Point pt = Perspective.localToCanvas(client, lp, plane);
                                                            if (pt != null) objData.put("tileCanvas", java.util.Map.of("x", pt.getX(), "y", pt.getY()));
                                                        }
                                                    } catch (Exception ignored) {}

                                                    candidates.add(objData);
                                                }
                                            }
                                        }

                                        // Select best candidate: prioritize exact matches, then closest distance
                                        if (candidates.isEmpty()) {
                                            resp.put("ok", true);
                                            resp.put("found", false);
                                            resp.put("object", null);
                                        } else {
                                            // Sort candidates: exact matches first, then by distance
                                            candidates.sort((a, b) -> {
                                                boolean aExact = (Boolean) a.getOrDefault("exactMatch", false);
                                                boolean bExact = (Boolean) b.getOrDefault("exactMatch", false);
                                                
                                                if (aExact && !bExact) return -1;  // a is exact, b is not
                                                if (!aExact && bExact) return 1;   // b is exact, a is not
                                                
                                                // Both same type (exact or not), sort by distance
                                                int aDist = (Integer) a.getOrDefault("distance", Integer.MAX_VALUE);
                                                int bDist = (Integer) b.getOrDefault("distance", Integer.MAX_VALUE);
                                                return Integer.compare(aDist, bDist);
                                            });
                                            
                                            resp.put("ok", true);
                                            resp.put("found", true);
                                            resp.put("object", candidates.get(0));
                                        }

                                    } catch (Throwable t) {
                                        resp.put("ok", false); resp.put("err", "find-object-failed");
                                    } finally {
                                        fut.complete(resp);
                                    }
                                });

                                java.util.Map<String,Object> result;
                                try {
                                    result = fut.get(150, java.util.concurrent.TimeUnit.MILLISECONDS);
                                } catch (Exception e) {
                                    result = java.util.Map.of("ok", false, "err", "timeout");
                                }
                                out.println(gson.toJson(result));
                                break;
                            }

                            case "find_object_by_path": {
                                final String needle = (cmd.name == null ? "" : cmd.name.trim().toLowerCase());
                                final int radius = (cmd.radius == null) ? 26 : Math.max(1, cmd.radius);

                                // which object kinds to include
                                final java.util.Set<String> kinds = new java.util.HashSet<>();
                                if (cmd.types != null) {
                                    for (String t : cmd.types) if (t != null) kinds.add(t.trim().toUpperCase());
                                }
                                if (kinds.isEmpty()) {
                                    kinds.add("GAME");
                                    kinds.add("WALL");
                                    kinds.add("DECOR");
                                    kinds.add("GROUND");
                                }

                                final java.util.concurrent.CompletableFuture<java.util.Map<String,Object>> fut =
                                        new java.util.concurrent.CompletableFuture<>();

                                clientThread.invokeLater(() -> {
                                    final java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
                                    try {
                                        final Player me = client.getLocalPlayer();
                                        final Scene scene = client.getScene();
                                        if (me == null || scene == null) { 
                                            resp.put("ok", false); resp.put("err", "no-player-or-scene"); 
                                            fut.complete(resp); return; 
                                        }

                                        final int plane = client.getPlane();
                                        final int baseX = client.getBaseX(), baseY = client.getBaseY();
                                        final int myWx = me.getWorldLocation().getX();
                                        final int myWy = me.getWorldLocation().getY();
                                        final int minWx = myWx - radius, maxWx = myWx + radius;
                                        final int minWy = myWy - radius, maxWy = myWy + radius;

                                        final Tile[][][] tiles = scene.getTiles();
                                        if (tiles == null || plane < 0 || plane >= tiles.length || tiles[plane] == null) {
                                            resp.put("ok", false); resp.put("err", "no-tiles"); 
                                            fut.complete(resp); return;
                                        }

                                        // Helper to check if object matches name
                                        java.util.function.Predicate<TileObject> nameMatches = (obj) -> {
                                            if (obj == null) return false;
                                            final int id = obj.getId();
                                            final ObjectComposition baseComp = client.getObjectDefinition(id);
                                            final ObjectComposition comp = resolveObjectComposition(client, baseComp);
                                            String nm = (comp != null && comp.getName() != null) ? comp.getName() : "";
                                            
                                            final String nmLower = nm.toLowerCase();
                                            if (needle.isEmpty()) return true;
                                            
                                            // For "tree", be more specific - avoid gates, doors, etc.
                                            if ("tree".equals(needle)) {
                                                return nmLower.equals("tree") || 
                                                       nmLower.startsWith("tree ") || 
                                                       nmLower.endsWith(" tree") ||
                                                       (nmLower.contains("tree") && !nmLower.contains("gate") && !nmLower.contains("door"));
                                            }
                                            
                                            // For other objects, use normal matching
                                            return nmLower.equals(needle) || nmLower.contains(needle);
                                        };

                                        // Collect all matching objects first
                                        java.util.List<java.util.Map<String,Object>> candidates = new java.util.ArrayList<>();
                                        
                                        final int minLx = Math.max(0, minWx - baseX), maxLx = Math.min(103, maxWx - baseX);
                                        final int minLy = Math.max(0, minWy - baseY), maxLy = Math.min(103, maxWy - baseY);
                                        
                                        for (int lx = minLx; lx <= maxLx; lx++) {
                                            for (int ly = minLy; ly <= maxLy; ly++) {
                                                final Tile t = tiles[plane][lx][ly];
                                                if (t == null) continue;

                                                // Check all object types
                                                if (kinds.contains("WALL")) {
                                                    TileObject obj = t.getWallObject();
                                                    if (obj != null && nameMatches.test(obj)) {
                                                        candidates.add(createObjectData(obj, "WALL", baseX, baseY, plane, client, needle));
                                                    }
                                                }
                                                if (kinds.contains("DECOR")) {
                                                    TileObject obj = t.getDecorativeObject();
                                                    if (obj != null && nameMatches.test(obj)) {
                                                        candidates.add(createObjectData(obj, "DECOR", baseX, baseY, plane, client, needle));
                                                    }
                                                }
                                                if (kinds.contains("GROUND")) {
                                                    TileObject obj = t.getGroundObject();
                                                    if (obj != null && nameMatches.test(obj)) {
                                                        candidates.add(createObjectData(obj, "GROUND", baseX, baseY, plane, client, needle));
                                                    }
                                                }
                                                if (kinds.contains("GAME")) {
                                                    final GameObject[] arr = t.getGameObjects();
                                                    if (arr != null) {
                                                        for (GameObject go : arr) {
                                                            if (go != null && nameMatches.test(go)) {
                                                                candidates.add(createObjectData(go, "GAME", baseX, baseY, plane, client, needle));
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        if (candidates.isEmpty()) {
                                            resp.put("ok", true);
                                            resp.put("found", false);
                                            resp.put("object", null);
                                        } else {
                                            // Find object with shortest path
                                            java.util.Map<String,Object> bestObject = null;
                                            int shortestPathLength = Integer.MAX_VALUE;
                                            
                                            for (java.util.Map<String,Object> candidate : candidates) {
                                                @SuppressWarnings("unchecked")
                                                java.util.Map<String,Object> world = (java.util.Map<String,Object>) candidate.get("world");
                                                int objWx = (Integer) world.get("x");
                                                int objWy = (Integer) world.get("y");
                                                
                                                // Compute path to this object
                                                PathResult pathResult = computePathTowardScene(objWx, objWy);
                                                if (pathResult != null && pathResult.path != null && !pathResult.path.isEmpty()) {
                                                    int pathLength = pathResult.path.size();
                                                    if (pathLength < shortestPathLength) {
                                                        shortestPathLength = pathLength;
                                                        bestObject = candidate;
                                                        bestObject.put("pathLength", pathLength);
                                                        bestObject.put("pathFound", true);
                                                    }
                                                } else {
                                                    // If pathfinding fails, fall back to raw distance
                                                    int rawDistance = (Integer) candidate.getOrDefault("distance", Integer.MAX_VALUE);
                                                    if (rawDistance < shortestPathLength) {
                                                        shortestPathLength = rawDistance;
                                                        bestObject = candidate;
                                                        bestObject.put("pathLength", rawDistance);
                                                        bestObject.put("pathFound", false);
                                                    }
                                                }
                                            }
                                            
                                            if (bestObject != null) {
                                                resp.put("ok", true);
                                                resp.put("found", true);
                                                resp.put("object", bestObject);
                                            } else {
                                                resp.put("ok", true);
                                                resp.put("found", false);
                                                resp.put("object", null);
                                            }
                                        }

                                    } catch (Throwable t) {
                                        resp.put("ok", false); resp.put("err", "find-object-by-path-failed");
                                    } finally {
                                        fut.complete(resp);
                                    }
                                });

                                java.util.Map<String,Object> result;
                                try {
                                    result = fut.get(500, java.util.concurrent.TimeUnit.MILLISECONDS); // Longer timeout for pathfinding
                                } catch (Exception e) {
                                    result = java.util.Map.of("ok", false, "err", "timeout");
                                }
                                out.println(gson.toJson(result));
                                break;
                            }

                            case "get_object_at_tile": {
                                final int targetX = cmd.x;
                                final int targetY = cmd.y;
                                final int targetPlane = (cmd.plane != null) ? cmd.plane : client.getPlane();
                                final String needle = (cmd.name == null ? "" : cmd.name.trim().toLowerCase());

                                // which object kinds to include
                                final java.util.Set<String> kinds = new java.util.HashSet<>();
                                if (cmd.types != null) {
                                    for (String t : cmd.types) if (t != null) kinds.add(t.trim().toUpperCase());
                                }
                                if (kinds.isEmpty()) {
                                    kinds.add("GAME");
                                    kinds.add("WALL");
                                    kinds.add("DECOR");
                                    kinds.add("GROUND");
                                }

                                final java.util.concurrent.CompletableFuture<java.util.Map<String,Object>> fut =
                                        new java.util.concurrent.CompletableFuture<>();

                                clientThread.invokeLater(() -> {
                                    final java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
                                    try {
                                        final Player me = client.getLocalPlayer();
                                        final Scene scene = client.getScene();
                                        if (me == null || scene == null) { 
                                            resp.put("ok", false); resp.put("err", "no-player-or-scene"); 
                                            fut.complete(resp); return; 
                                        }

                                        final int plane = client.getPlane();
                                        final int baseX = client.getBaseX(), baseY = client.getBaseY();
                                        
                                        // Convert world coordinates to local coordinates
                                        final int lx = targetX - baseX;
                                        final int ly = targetY - baseY;
                                        
                                        // Check if target tile is in scene bounds
                                        if (lx < 0 || lx >= 104 || ly < 0 || ly >= 104 || targetPlane != plane) {
                                            resp.put("ok", false); resp.put("err", "tile-out-of-scene"); 
                                            fut.complete(resp); return;
                                        }

                                        final Tile[][][] tiles = scene.getTiles();
                                        if (tiles == null || plane < 0 || plane >= tiles.length || tiles[plane] == null) {
                                            resp.put("ok", false); resp.put("err", "no-tiles"); 
                                            fut.complete(resp); return;
                                        }

                                        final Tile targetTile = tiles[plane][lx][ly];
                                        if (targetTile == null) {
                                            resp.put("ok", false); resp.put("err", "no-tile"); 
                                            fut.complete(resp); return;
                                        }

                                        final java.util.List<java.util.Map<String,Object>> objects = new java.util.ArrayList<>();

                                        // Check all object types on this tile
                                        if (kinds.contains("GAME")) {
                                            final GameObject[] gameObjects = targetTile.getGameObjects();
                                            if (gameObjects != null) {
                                                for (GameObject obj : gameObjects) {
                                                    if (obj != null) {
                                                        java.util.Map<String,Object> objData = createObjectData(obj, "GAME", baseX, baseY, plane, client, needle);
                                                        if (objData != null) {
                                                            objects.add(objData);
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        if (kinds.contains("WALL")) {
                                            final WallObject wallObject = targetTile.getWallObject();
                                            if (wallObject != null) {
                                                java.util.Map<String,Object> objData = createObjectData(wallObject, "WALL", baseX, baseY, plane, client, needle);
                                                if (objData != null) {
                                                    objects.add(objData);
                                                }
                                            }
                                        }

                                        if (kinds.contains("DECOR")) {
                                            final DecorativeObject decorativeObject = targetTile.getDecorativeObject();
                                            if (decorativeObject != null) {
                                                java.util.Map<String,Object> objData = createObjectData(decorativeObject, "DECOR", baseX, baseY, plane, client, needle);
                                                if (objData != null) {
                                                    objects.add(objData);
                                                }
                                            }
                                        }

                                        if (kinds.contains("GROUND")) {
                                            final GroundObject groundObject = targetTile.getGroundObject();
                                            if (groundObject != null) {
                                                java.util.Map<String,Object> objData = createObjectData(groundObject, "GROUND", baseX, baseY, plane, client, needle);
                                                if (objData != null) {
                                                    objects.add(objData);
                                                }
                                            }
                                        }

                                        resp.put("ok", true);
                                        resp.put("objects", objects);
                                        resp.put("count", objects.size());

                                    } catch (Throwable t) {
                                        resp.put("ok", false); resp.put("err", "get-object-at-tile-failed");
                                    } finally {
                                        fut.complete(resp);
                                    }
                                });

                                java.util.Map<String,Object> result;
                                try {
                                    result = fut.get(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                                } catch (Exception e) {
                                    result = java.util.Map.of("ok", false, "err", "timeout");
                                }
                                out.println(gson.toJson(result));
                                break;
                            }

                            case "find_object_in_area": {
                                final String needle = (cmd.name == null ? "" : cmd.name.trim().toLowerCase());
                                final int minX = cmd.minX;
                                final int maxX = cmd.maxX;
                                final int minY = cmd.minY;
                                final int maxY = cmd.maxY;
                                final String objectType = (cmd.types != null && !cmd.types.isEmpty()) ? cmd.types.get(0).trim().toUpperCase() : "GAME";

                                final java.util.concurrent.CompletableFuture<java.util.Map<String,Object>> fut =
                                        new java.util.concurrent.CompletableFuture<>();

                                clientThread.invokeLater(() -> {
                                    final java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
                                    try {
                                        final Player me = client.getLocalPlayer();
                                        final Scene scene = client.getScene();
                                        if (me == null || scene == null) { 
                                            resp.put("ok", false); resp.put("err", "no-player-or-scene"); 
                                            fut.complete(resp); return; 
                                        }

                                        final int plane = client.getPlane();
                                        final int baseX = client.getBaseX(), baseY = client.getBaseY();
                                        final int myWx = me.getWorldLocation().getX();
                                        final int myWy = me.getWorldLocation().getY();

                                        final Tile[][][] tiles = scene.getTiles();
                                        if (tiles == null || plane < 0 || plane >= tiles.length || tiles[plane] == null) {
                                            resp.put("ok", false); resp.put("err", "no-tiles"); 
                                            fut.complete(resp); return;
                                        }

                                        // Helper to check if object matches name early
                                        java.util.function.Predicate<TileObject> nameMatches = (obj) -> {
                                            if (obj == null) return false;
                                            final int id = obj.getId();
                                            final ObjectComposition baseComp = client.getObjectDefinition(id);
                                            final ObjectComposition comp = resolveObjectComposition(client, baseComp);
                                            String nm = (comp != null && comp.getName() != null) ? comp.getName() : "";
                                            
                                            final String nmLower = nm.toLowerCase();
                                            if (needle.isEmpty()) return true;
                                            
                                            // For "tree", be more specific - avoid gates, doors, etc.
                                            if ("tree".equals(needle)) {
                                                return nmLower.equals("tree") || 
                                                       nmLower.startsWith("tree ") || 
                                                       nmLower.endsWith(" tree") ||
                                                       (nmLower.contains("tree") && !nmLower.contains("gate") && !nmLower.contains("door"));
                                            }
                                            
                                            // For other objects, use normal matching
                                            return nmLower.equals(needle) || nmLower.contains(needle);
                                        };

                                        // Convert area bounds to local coordinates
                                        final int minLx = Math.max(0, minX - baseX);
                                        final int maxLx = Math.min(103, maxX - baseX);
                                        final int minLy = Math.max(0, minY - baseY);
                                        final int maxLy = Math.min(103, maxY - baseY);
                                        
                                        java.util.List<java.util.Map<String,Object>> candidates = new java.util.ArrayList<>();
                                        
                                        // Scan only the specified area
                                        for (int lx = minLx; lx <= maxLx; lx++) {
                                            for (int ly = minLy; ly <= maxLy; ly++) {
                                                final Tile t = tiles[plane][lx][ly];
                                                if (t == null) continue;

                                                TileObject obj = null;
                                                if ("WALL".equals(objectType)) obj = t.getWallObject();
                                                else if ("DECOR".equals(objectType)) obj = t.getDecorativeObject();
                                                else if ("GROUND".equals(objectType)) obj = t.getGroundObject();
                                                else if ("GAME".equals(objectType)) {
                                                    final GameObject[] arr = t.getGameObjects();
                                                    if (arr != null) {
                                                        for (GameObject go : arr) {
                                                            if (go != null && nameMatches.test(go)) {
                                                                obj = go;
                                                                break;
                                                            }
                                                        }
                                                    }
                                                }

                                                if (obj != null && nameMatches.test(obj)) {
                                                    // Add to candidates list for later selection
                                                    final int id = obj.getId();
                                                    final ObjectComposition baseComp = client.getObjectDefinition(id);
                                                    final ObjectComposition comp = resolveObjectComposition(client, baseComp);
                                                    String nm = (comp != null && comp.getName() != null) ? comp.getName() : "";
                                                    String[] actions = (comp != null) ? comp.getActions() : null;

                                                    final int wx = baseX + obj.getLocalLocation().getSceneX();
                                                    final int wy = baseY + obj.getLocalLocation().getSceneY();
                                                    final int distance = Math.abs(wx - myWx) + Math.abs(wy - myWy);

                                                    final java.util.Map<String,Object> objData = new java.util.LinkedHashMap<>();
                                                    objData.put("id", id);
                                                    objData.put("type", objectType);
                                                    objData.put("name", nm);
                                                    objData.put("world", java.util.Map.of("x", wx, "y", wy, "p", plane));
                                                    objData.put("distance", distance);
                                                    objData.put("exactMatch", nm.toLowerCase().equals(needle));

                                                    if (actions != null) {
                                                        java.util.List<String> al = new java.util.ArrayList<>();
                                                        for (String a : actions) if (a != null) al.add(a);
                                                        objData.put("actions", al);
                                                    }

                                                    // Get bounds/canvas position
                                                    try {
                                                        final java.awt.Shape hull = (obj instanceof GameObject) ? ((GameObject)obj).getConvexHull()
                                                                : (obj instanceof DecorativeObject) ? ((DecorativeObject)obj).getConvexHull()
                                                                : (obj instanceof WallObject) ? ((WallObject)obj).getConvexHull()
                                                                : (obj instanceof GroundObject) ? ((GroundObject)obj).getConvexHull()
                                                                : null;
                                                        if (hull != null) {
                                                            final java.awt.Rectangle rb = hull.getBounds();
                                                            if (rb != null) {
                                                                objData.put("bounds", java.util.Map.of(
                                                                        "x", rb.x, "y", rb.y, "width", rb.width, "height", rb.height));
                                                                objData.put("canvas", java.util.Map.of(
                                                                        "x", rb.x + rb.width/2, "y", rb.y + rb.height/2));
                                                                objData.put("orientation", (rb.width > rb.height) ? "horizontal" : "vertical");
                                                            }
                                                        }
                                                    } catch (Exception ignored) {}

                                                    // Fallback: tile center projection
                                                    try {
                                                        final LocalPoint lp = LocalPoint.fromWorld(client, wx, wy);
                                                        if (lp != null) {
                                                            final net.runelite.api.Point pt = Perspective.localToCanvas(client, lp, plane);
                                                            if (pt != null) objData.put("tileCanvas", java.util.Map.of("x", pt.getX(), "y", pt.getY()));
                                                        }
                                                    } catch (Exception ignored) {}

                                                    candidates.add(objData);
                                                }
                                            }
                                        }

                                        // Select best candidate: prioritize exact matches, then closest distance
                                        if (candidates.isEmpty()) {
                                            resp.put("ok", true);
                                            resp.put("found", false);
                                            resp.put("object", null);
                                        } else {
                                            // Sort candidates: exact matches first, then by distance
                                            candidates.sort((a, b) -> {
                                                boolean aExact = (Boolean) a.getOrDefault("exactMatch", false);
                                                boolean bExact = (Boolean) b.getOrDefault("exactMatch", false);
                                                
                                                if (aExact && !bExact) return -1;  // a is exact, b is not
                                                if (!aExact && bExact) return 1;   // b is exact, a is not
                                                
                                                // Both same type (exact or not), sort by distance
                                                int aDist = (Integer) a.getOrDefault("distance", Integer.MAX_VALUE);
                                                int bDist = (Integer) b.getOrDefault("distance", Integer.MAX_VALUE);
                                                return Integer.compare(aDist, bDist);
                                            });

                                            resp.put("ok", true);
                                            resp.put("found", true);
                                            resp.put("object", candidates.get(0));
                                        }

                                    } catch (Throwable t) {
                                        resp.put("ok", false); resp.put("err", "find-object-in-area-failed");
                                    } finally {
                                        fut.complete(resp);
                                    }
                                });

                                java.util.Map<String,Object> result;
                                try {
                                    result = fut.get(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                                } catch (Exception e) {
                                    result = java.util.Map.of("ok", false, "err", "timeout");
                                }
                                out.println(gson.toJson(result));
                                break;
                            }

                            case "find_npc": {
                                final String needle = (cmd.name == null ? "" : cmd.name.trim().toLowerCase());
                                final int radius = (cmd.radius == null) ? 26 : Math.max(1, cmd.radius);

                                final java.util.concurrent.CompletableFuture<java.util.Map<String,Object>> fut =
                                        new java.util.concurrent.CompletableFuture<>();

                                clientThread.invokeLater(() -> {
                                    final java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
                                    try {
                                        final Player me = client.getLocalPlayer();
                                        final Scene scene = client.getScene();
                                        if (me == null || scene == null) { 
                                            resp.put("ok", false); resp.put("err", "no-player-or-scene"); 
                                            fut.complete(resp); return; 
                                        }

                                        final int plane = client.getPlane();
                                        final int baseX = client.getBaseX(), baseY = client.getBaseY();
                                        final int myWx = me.getWorldLocation().getX();
                                        final int myWy = me.getWorldLocation().getY();
                                        final int minWx = myWx - radius, maxWx = myWx + radius;
                                        final int minWy = myWy - radius, maxWy = myWy + radius;

                                        // Get all NPCs from the client
                                        final java.util.List<NPC> npcs = client.getNpcs();
                                        if (npcs == null) { 
                                            resp.put("ok", true); resp.put("found", false); resp.put("npc", null);
                                            fut.complete(resp); return; 
                                        }

                                        // Collect all matching NPCs and find the closest one
                                        java.util.List<java.util.Map<String,Object>> candidates = new java.util.ArrayList<>();
                                        
                                        for (NPC npc : npcs) {
                                            if (npc == null) continue;

                                            // Get NPC composition for name and actions
                                            final NPCComposition comp = client.getNpcDefinition(npc.getId());
                                            final String nm = (comp != null && comp.getName() != null) ? comp.getName() : "";
                                            final String nmLower = nm.toLowerCase();
                                            if (!needle.isEmpty() && !nmLower.contains(needle)) continue;

                                            // Get world coordinates
                                            final int wx = npc.getWorldLocation().getX();
                                            final int wy = npc.getWorldLocation().getY();
                                            if (wx < minWx || wx > maxWx || wy < minWy || wy > maxWy) continue;

                                            // Build NPC data
                                            final java.util.Map<String,Object> npcData = new java.util.LinkedHashMap<>();
                                            npcData.put("type", "NPC");
                                            npcData.put("id", npc.getId());
                                            npcData.put("name", nm);
                                            npcData.put("actions", (comp != null) ? comp.getActions() : null);
                                            npcData.put("world", java.util.Map.of("x", wx, "y", wy, "p", plane));
                                            npcData.put("distance", Math.abs(wx - myWx) + Math.abs(wy - myWy));
                                            
                                            // Health info (only known when a health bar is active; otherwise -1/-1)
                                            try {
                                                npcData.put("healthRatio", npc.getHealthRatio());
                                                npcData.put("healthScale", npc.getHealthScale());
                                            } catch (Throwable ignored) {
                                                npcData.put("healthRatio", -1);
                                                npcData.put("healthScale", -1);
                                            }
                                            
                                            // Add combat status
                                            Actor interacting = npc.getInteracting();
                                            boolean inCombat = (interacting != null);
                                            npcData.put("inCombat", inCombat);
                                            if (inCombat) {
                                                npcData.put("combatTarget", interacting.getName());
                                                npcData.put("combatTargetType", interacting instanceof Player ? "Player" : "NPC");
                                            }

                                            // Get canvas position from convex hull if available
                                            try {
                                                final java.awt.Shape hull = npc.getConvexHull();
                                                if (hull != null) {
                                                    final java.awt.Rectangle rb = hull.getBounds();
                                                    if (rb != null) {
                                                        npcData.put("bounds", java.util.Map.of(
                                                                "x", rb.x, "y", rb.y, "width", rb.width, "height", rb.height));
                                                        npcData.put("canvas", java.util.Map.of(
                                                                "x", rb.x + rb.width/2, "y", rb.y + rb.height/2));
                                                        npcData.put("orientation", (rb.width > rb.height) ? "horizontal" : "vertical");
                                                    }
                                                }
                                            } catch (Exception ignored) {}

                                            // Fallback: tile center projection
                                            try {
                                                final LocalPoint lp = LocalPoint.fromWorld(client, wx, wy);
                                                if (lp != null) {
                                                    final net.runelite.api.Point pt = Perspective.localToCanvas(client, lp, plane);
                                                    if (pt != null) npcData.put("tileCanvas", java.util.Map.of("x", pt.getX(), "y", pt.getY()));
                                                }
                                            } catch (Exception ignored) {}

                                            candidates.add(npcData);
                                        }
                                        
                                        // Select closest NPC
                                        if (candidates.isEmpty()) {
                                            resp.put("ok", true);
                                            resp.put("found", false);
                                            resp.put("npc", null);
                                        } else {
                                            // Sort by distance and return closest
                                            candidates.sort((a, b) -> {
                                                int aDist = (Integer) a.getOrDefault("distance", Integer.MAX_VALUE);
                                                int bDist = (Integer) b.getOrDefault("distance", Integer.MAX_VALUE);
                                                return Integer.compare(aDist, bDist);
                                            });
                                            
                                            resp.put("ok", true);
                                            resp.put("found", true);
                                            resp.put("npc", candidates.get(0));
                                        }

                                    } catch (Throwable t) {
                                        resp.put("ok", false); resp.put("err", "find-npc-failed");
                                    } finally {
                                        fut.complete(resp);
                                    }
                                });

                                java.util.Map<String,Object> result;
                                try {
                                    result = fut.get(150, java.util.concurrent.TimeUnit.MILLISECONDS);
                                } catch (Exception e) {
                                    result = java.util.Map.of("ok", false, "err", "timeout");
                                }
                                out.println(gson.toJson(result));
                                break;
                            }

                            case "scan_scene": {
                                final java.util.concurrent.CompletableFuture<java.util.Map<String,Object>> fut =
                                        new java.util.concurrent.CompletableFuture<>();

                                clientThread.invokeLater(() -> {
                                    final java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
                                    try {
                                        final Player me = client.getLocalPlayer();
                                        if (me == null) {
                                            resp.put("ok", false); resp.put("err", "no-player");
                                            fut.complete(resp); return;
                                        }

                                        final int plane = client.getPlane();
                                        final int baseX = client.getBaseX(), baseY = client.getBaseY();
                                        
                                        // Use RuneLite's existing collision system
                                        var cms = client.getCollisionMaps();
                                        if (cms == null || plane < 0 || plane >= cms.length || cms[plane] == null) {
                                            resp.put("ok", false); resp.put("err", "no-collision-maps");
                                            fut.complete(resp); return;
                                        }
                                        
                                        int[][] flags = cms[plane].getFlags();
                                        
                                        // Scan the current 104x104 scene for collision data using RuneLite's collision flags
                                        java.util.List<java.util.Map<String,Object>> collisionData = new java.util.ArrayList<>();
                                        
                                        for (int lx = 0; lx < 104; lx++) {
                                            for (int ly = 0; ly < 104; ly++) {
                                                final int worldX = baseX + lx;
                                                final int worldY = baseY + ly;
                                                
                                                // Get collision flags from CollisionDataFlag
                                                int tileFlags = client.getCollisionMaps()[plane].getFlags()[lx][ly];
                                                
                                                // Skip scene boundary tiles that have all flags set (0x00FFFFFF)
                                                // These are not real collision data but scene boundaries
                                                if (tileFlags == 0x00FFFFFF) {
                                                    continue;
                                                }
                                                
                                                // Detect doors, ladders using object actions
                                                java.util.Map<String,Object> doorInfo = null;
                                                boolean ladderUp = false;
                                                boolean ladderDown = false;
                                                
                                                try {
                                                    final Scene scene = client.getScene();
                                                    if (scene != null) {
                                                        final Tile[][][] tiles = scene.getTiles();
                                                        if (tiles != null && plane < tiles.length && tiles[plane] != null) {
                                                            final Tile tile = tiles[plane][lx][ly];
                                                            if (tile != null) {
                                                                // Check WallObject for doors
                                                                WallObject wallObj = tile.getWallObject();
                                                                if (wallObj != null) {
                                                                    try {
                                                                        ObjectComposition comp = client.getObjectDefinition(wallObj.getId());
                                                                        if (comp != null) {
                                                                            String[] actions = comp.getActions();
                                                                            if (actions != null) {
                                                                                for (String action : actions) {
                                                                                    if (action != null) {
                                                                                        String actionLower = action.toLowerCase();
                                                                                        if (actionLower.contains("open") || actionLower.contains("close")) {
                                                                                            // Create door info in old format
                                                                                            doorInfo = new java.util.LinkedHashMap<>();
                                                                                            doorInfo.put("id", wallObj.getId());
                                                                                            doorInfo.put("passable", false);
                                                                                            doorInfo.put("actions", actions);
                                                                                            doorInfo.put("orientationA", wallObj.getOrientationA());
                                                                                            doorInfo.put("orientationB", wallObj.getOrientationB());
                                                                                        }
                                                                                    }
                                                                                }
                                                                            }
                                                                        }
                                                                    } catch (Exception ignored) {}
                                                                }
                                                                
                                                                // Check GameObjects for ladders
                                                                GameObject[] gameObjects = tile.getGameObjects();
                                                                if (gameObjects != null) {
                                                                    for (GameObject gameObj : gameObjects) {
                                                                        if (gameObj != null) {
                                                                            try {
                                                                                ObjectComposition comp = client.getObjectDefinition(gameObj.getId());
                                                                                if (comp != null) {
                                                                                    String[] actions = comp.getActions();
                                                                                    if (actions != null) {
                                                                                        for (String action : actions) {
                                                                                            if (action != null) {
                                                                                                String actionLower = action.toLowerCase();
                                                                                                if (actionLower.contains("climb-up") || actionLower.contains("climb up")) {
                                                                                                    ladderUp = true;
                                                                                                }
                                                                                                if (actionLower.contains("climb-down") || actionLower.contains("climb down")) {
                                                                                                    ladderDown = true;
                                                                                                }
                                                                                            }
                                                                                        }
                                                                                    }
                                                                                }
                                                                            } catch (Exception ignored) {}
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                } catch (Exception ignored) {}
                                                
                                                // Emit all tiles with collision data
                                                java.util.Map<String,Object> tileData = new java.util.LinkedHashMap<>();
                                                tileData.put("x", worldX);
                                                tileData.put("y", worldY);
                                                tileData.put("p", plane);
                                                tileData.put("flags", tileFlags);
                                                if (doorInfo != null) {
                                                    tileData.put("door", doorInfo);
                                                }
                                                tileData.put("ladderUp", ladderUp);
                                                tileData.put("ladderDown", ladderDown);
                                                
                                                collisionData.add(tileData);
                                            }
                                        }

                                        resp.put("ok", true);
                                        resp.put("baseX", baseX);
                                        resp.put("baseY", baseY);
                                        resp.put("plane", plane);
                                        resp.put("collisionData", collisionData);
                                        resp.put("count", collisionData.size());

                                    } catch (Throwable t) {
                                        resp.put("ok", false); resp.put("err", "scan-scene-failed");
                                    } finally {
                                        fut.complete(resp);
                                    }
                                });

                                java.util.Map<String,Object> result;
                                try {
                                    result = fut.get(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                                } catch (Exception e) {
                                    result = java.util.Map.of("ok", false, "err", "timeout");
                                }
                                out.println(gson.toJson(result));
                                break;
                            }

                            case "detect_water": {
                                final java.util.concurrent.CompletableFuture<java.util.Map<String,Object>> fut =
                                        new java.util.concurrent.CompletableFuture<>();

                                clientThread.invokeLater(() -> {
                                    final java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
                                    try {
                                        final Player me = client.getLocalPlayer();
                                        if (me == null) {
                                            resp.put("ok", false); resp.put("err", "no-player");
                                            fut.complete(resp); return;
                                        }

                                        final int plane = client.getPlane();
                                        final int baseX = client.getBaseX(), baseY = client.getBaseY();
                                        
                                        // Use RuneLite's collision system to detect water
                                        var cms = client.getCollisionMaps();
                                        if (cms == null || plane < 0 || plane >= cms.length || cms[plane] == null) {
                                            resp.put("ok", false); resp.put("err", "no-collision-maps");
                                            fut.complete(resp); return;
                                        }
                                        
                                        int[][] flags = cms[plane].getFlags();
                                        
                                        // Detect water regions using collision flags
                                        java.util.List<java.util.Map<String,Object>> waterRegions = new java.util.ArrayList<>();
                                        boolean[][] visited = new boolean[104][104];
                                        
                                        for (int lx = 0; lx < 104; lx++) {
                                            for (int ly = 0; ly < 104; ly++) {
                                                if (visited[lx][ly]) continue;
                                                
                                                // Check if this tile is water using collision flags
                                                int f = flags[lx][ly];
                                                boolean isWater = (f & 0x100) != 0; // Water collision flag
                                                
                                                if (!isWater) continue;
                                                
                                                // Flood fill to find connected water region
                                                java.util.List<java.util.Map<String,Object>> region = new java.util.ArrayList<>();
                                                java.util.Queue<int[]> queue = new java.util.LinkedList<>();
                                                queue.offer(new int[]{lx, ly});
                                                
                                                while (!queue.isEmpty()) {
                                                    int[] current = queue.poll();
                                                    int cx = current[0], cy = current[1];
                                                    
                                                    if (visited[cx][cy] || cx < 0 || cx >= 104 || cy < 0 || cy >= 104) continue;
                                                    
                                                    int cf = flags[cx][cy];
                                                    boolean isWaterTile = (cf & 0x100) != 0;
                                                    if (!isWaterTile) continue;
                                                    
                                                    visited[cx][cy] = true;
                                                    region.add(java.util.Map.of(
                                                        "x", baseX + cx,
                                                        "y", baseY + cy,
                                                        "p", plane
                                                    ));
                                                    
                                                    // Add neighbors
                                                    queue.offer(new int[]{cx + 1, cy});
                                                    queue.offer(new int[]{cx - 1, cy});
                                                    queue.offer(new int[]{cx, cy + 1});
                                                    queue.offer(new int[]{cx, cy - 1});
                                                }
                                                
                                                if (region.size() > 0) {
                                                    waterRegions.add(java.util.Map.of(
                                                        "region", region,
                                                        "size", region.size()
                                                    ));
                                                }
                                            }
                                        }

                                        resp.put("ok", true);
                                        resp.put("baseX", baseX);
                                        resp.put("baseY", baseY);
                                        resp.put("plane", plane);
                                        resp.put("waterRegions", waterRegions);
                                        resp.put("count", waterRegions.size());

                                    } catch (Throwable t) {
                                        resp.put("ok", false); resp.put("err", "detect-water-failed");
                                    } finally {
                                        fut.complete(resp);
                                    }
                                });

                                java.util.Map<String,Object> result;
                                try {
                                    result = fut.get(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                                } catch (Exception e) {
                                    result = java.util.Map.of("ok", false, "err", "timeout");
                                }
                                out.println(gson.toJson(result));
                                break;
                            }

                            case "hovered": {
                                final java.util.concurrent.CompletableFuture<java.util.Map<String,Object>> fut =
                                        new java.util.concurrent.CompletableFuture<>();

                                clientThread.invokeLater(() -> {
                                    final java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
                                    try {
                                        Tile hoveredTile = client.getSelectedSceneTile();
                                        if (hoveredTile != null) {
                                            java.util.Map<String,Object> tileInfo = new java.util.LinkedHashMap<>();
                                            WorldPoint worldPos = hoveredTile.getWorldLocation();
                                            tileInfo.put("worldX", worldPos.getX());
                                            tileInfo.put("worldY", worldPos.getY());
                                            tileInfo.put("plane", worldPos.getPlane());

                                            // Get canvas position
                                            try {
                                                LocalPoint lp = LocalPoint.fromWorld(client, worldPos.getX(), worldPos.getY());
                                                if (lp != null) {
                                                    net.runelite.api.Point pt = Perspective.localToCanvas(client, lp, worldPos.getPlane());
                                                    if (pt != null) {
                                                        tileInfo.put("canvasX", pt.getX());
                                                        tileInfo.put("canvasY", pt.getY());
                                                    }
                                                }
                                            } catch (Exception ignored) {}

                                            // Game objects on hovered tile
                                            GameObject[] hoveredObjects = hoveredTile.getGameObjects();
                                            java.util.List<java.util.Map<String,Object>> gameObjects = new java.util.ArrayList<>();

                                            for (int i = 0; i < hoveredObjects.length; i++) {
                                                GameObject object = hoveredObjects[i];
                                                if (object != null) {
                                                    java.util.Map<String,Object> objInfo = new java.util.LinkedHashMap<>();
                                                    objInfo.put("index", i);
                                                    objInfo.put("id", object.getId());

                                                    try {
                                                        ObjectComposition objDef = client.getObjectDefinition(object.getId());
                                                        objInfo.put("name", objDef.getName());
                                                        objInfo.put("actions", objDef.getActions());
                                                    } catch (Exception e) {
                                                        objInfo.put("name", "Unknown");
                                                        objInfo.put("actions", new String[0]);
                                                    }

                                                    WorldPoint objectPos = object.getWorldLocation();
                                                    objInfo.put("worldX", objectPos.getX());
                                                    objInfo.put("worldY", objectPos.getY());
                                                    objInfo.put("plane", objectPos.getPlane());

                                                    try {
                                                        net.runelite.api.Point canvasPos = object.getCanvasLocation();
                                                        objInfo.put("canvasX", canvasPos.getX());
                                                        objInfo.put("canvasY", canvasPos.getY());
                                                    } catch (Exception ignored) {}

                                                    try {
                                                        java.awt.Rectangle objectRect = object.getClickbox().getBounds();
                                                        java.util.Map<String,Object> clickbox = new java.util.LinkedHashMap<>();
                                                        clickbox.put("x", objectRect.x);
                                                        clickbox.put("y", objectRect.y);
                                                        clickbox.put("width", objectRect.width);
                                                        clickbox.put("height", objectRect.height);
                                                        objInfo.put("clickbox", clickbox);
                                                    } catch (Exception e) {
                                                        objInfo.put("clickbox", null);
                                                    }

                                                    gameObjects.add(objInfo);
                                                }
                                            }

                                            tileInfo.put("gameObjects", gameObjects);
                                            resp.put("ok", true);
                                            resp.put("hoveredTile", tileInfo);
                                        } else {
                                            resp.put("ok", true);
                                            resp.put("hoveredTile", null);
                                        }
                                    } catch (Throwable t) {
                                        resp.put("ok", false);
                                        resp.put("err", "hovered-failed");
                                    } finally {
                                        fut.complete(resp);
                                    }
                                });

                                java.util.Map<String,Object> result;
                                try {
                                    result = fut.get(150, java.util.concurrent.TimeUnit.MILLISECONDS);
                                } catch (Exception e) {
                                    result = java.util.Map.of("ok", false, "err", "timeout");
                                }
                                out.println(gson.toJson(result));
                                break;
                            }

                            case "tab": {
                                final java.util.concurrent.CompletableFuture<java.util.Map<String,Object>> fut =
                                        new java.util.concurrent.CompletableFuture<>();

                                clientThread.invokeLater(() -> {
                                    final java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
                                    try {
                                        // Map tab indices to names
                                        final String[] tabNames = {
                                            "COMBAT", "SKILLS", "QUESTS", "INVENTORY", "EQUIPMENT", 
                                            "PRAYER", "SPELLBOOK", "CHAT-CHANNEL", "ACCOUNT_MANAGEMENT", "FRIENDS_LIST", 
                                            "SETTINGS", "EMOTES", "MUSIC"
                                        };
                                        
                                        // Check which tab is selected by checking STONE widget SpriteId
                                        int currentTab = -1; // Unknown by default
                                        
                                        // Check SIDE_MOVABLE STONE widgets (0-6): STONE0 through STONE6
                                        // These correspond to tabs 0-6
                                        if (client.getWidget(164, 52) != null && !client.getWidget(164, 52).isHidden() && client.getWidget(164, 52).getSpriteId() == 1181) { // STONE0 - COMBAT
                                            currentTab = 0;
                                        } else if (client.getWidget(164, 53) != null && !client.getWidget(164, 53).isHidden() && client.getWidget(164, 53).getSpriteId() == 1181) { // STONE1 - SKILLS
                                            currentTab = 1;
                                        } else if (client.getWidget(164, 54) != null && !client.getWidget(164, 54).isHidden() && client.getWidget(164, 54).getSpriteId() == 1181) { // STONE2 - QUESTS
                                            currentTab = 2;
                                        } else if (client.getWidget(164, 55) != null && !client.getWidget(164, 55).isHidden() && client.getWidget(164, 55).getSpriteId() == 1181) { // STONE3 - INVENTORY
                                            currentTab = 3;
                                        } else if (client.getWidget(164, 56) != null && !client.getWidget(164, 56).isHidden() && client.getWidget(164, 56).getSpriteId() == 1181) { // STONE4 - EQUIPMENT
                                            currentTab = 4;
                                        } else if (client.getWidget(164, 57) != null && !client.getWidget(164, 57).isHidden() && client.getWidget(164, 57).getSpriteId() == 1181) { // STONE5 - PRAYER
                                            currentTab = 5;
                                        } else if (client.getWidget(164, 58) != null && !client.getWidget(164, 58).isHidden() && client.getWidget(164, 58).getSpriteId() == 1181) { // STONE6 - SPELLBOOK
                                            currentTab = 6;
                                        }
                                        // Check SIDE_STATIC STONE widgets (7-12): STONE7 through STONE13 (skip STONE10)
                                        // These correspond to tabs 7-12
                                        else if (client.getWidget(164, 38) != null && !client.getWidget(164, 38).isHidden() && client.getWidget(164, 38).getSpriteId() == 1181) { // STONE7 - CHAT-CHANNEL
                                            currentTab = 7;
                                        } else if (client.getWidget(164, 39) != null && !client.getWidget(164, 39).isHidden() && client.getWidget(164, 39).getSpriteId() == 1181) { // STONE8 - ACCOUNT MANAGEMENT
                                            currentTab = 8;
                                        } else if (client.getWidget(164, 40) != null && !client.getWidget(164, 40).isHidden() && client.getWidget(164, 40).getSpriteId() == 1181) { // STONE9 - FRIENDS LIST
                                            currentTab = 9;
                                        } else if (client.getWidget(164, 41) != null && !client.getWidget(164, 41).isHidden() && client.getWidget(164, 41).getSpriteId() == 1181) { // STONE11 - SETTINGS
                                            currentTab = 10;
                                        } else if (client.getWidget(164, 42) != null && !client.getWidget(164, 42).isHidden() && client.getWidget(164, 42).getSpriteId() == 1181) { // STONE12 - EMOTES
                                            currentTab = 11;
                                        } else if (client.getWidget(164, 43) != null && !client.getWidget(164, 43).isHidden() && client.getWidget(164, 43).getSpriteId() == 1181) { // STONE13 - MUSIC
                                            currentTab = 12;
                                        }
                                        
                                        // Add tab name mapping for better readability
                                        String tabName = getTabName(currentTab);
                                        
                                        resp.put("ok", true);
                                        resp.put("tab", currentTab);
                                        resp.put("tabName", tabName);
                                        
                                        // Get tab button coordinates dynamically
                                        java.util.List<java.util.Map<String,Object>> tabs = new java.util.ArrayList<>();
                                        
                                        // Try to get actual tab coordinates from ToplevelPreEoc ICON widgets
                                        try {
                                            // Map tab indices to ToplevelPreEoc ICON widget IDs
                                            // SIDE_MOVABLE (0-6): ICON0-ICON6 (164.59-164.65)
                                            // SIDE_STATIC (7-12): ICON7-ICON13 (164.44-164.49, skip 164.50)
                                            int[] tabWidgetIds = {
                                                59, 60, 61, 62, 63, 64, 65,  // ICON0-ICON6 (0-6)
                                                44, 45, 46, 47, 48, 49       // ICON7-ICON13 (7-12)
                                            };
                                            
                                            for (int i = 0; i < tabWidgetIds.length && i < tabNames.length; i++) {
                                                Widget tabWidget = client.getWidget(164, tabWidgetIds[i]);
                                                if (tabWidget != null) {
                                                    // Check if tab is visible (not hidden and has valid bounds)
                                                    boolean isHidden = tabWidget.isHidden();
                                                    java.awt.Rectangle bounds = tabWidget.getBounds();
                                                    boolean hasValidBounds = (bounds != null && bounds.x >= 0 && bounds.y >= 0);
                                                    
                                                    // Only add visible tabs
                                                    if (!isHidden && hasValidBounds) {
                                                        java.util.Map<String,Object> tabInfo = new java.util.LinkedHashMap<>();
                                                        tabInfo.put("index", i);
                                                        tabInfo.put("name", tabNames[i]);
                                                        tabInfo.put("bounds", java.util.Map.of(
                                                                "x", bounds.x, "y", bounds.y, 
                                                                "width", bounds.width, "height", bounds.height));
                                                        tabInfo.put("canvas", java.util.Map.of(
                                                                "x", bounds.x + bounds.width/2, 
                                                                "y", bounds.y + bounds.height/2));
                                                        tabs.add(tabInfo);
                                                    }
                                                }
                                            }
                                        } catch (Exception e) {
                                            // Fallback if widget access fails
                                        }
                                        
                                        // Only add tabs that are actually visible (have real coordinates)
                                        // Don't add fallback tabs - only return what's actually visible
                                        
                                        resp.put("tabs", tabs);
                                        
                                    } catch (Throwable t) {
                                        resp.put("ok", false);
                                        resp.put("err", "tab-failed");
                                    } finally {
                                        fut.complete(resp);
                                    }
                                });

                                java.util.Map<String,Object> result;
                                try {
                                    result = fut.get(150, java.util.concurrent.TimeUnit.MILLISECONDS);
                                } catch (Exception e) {
                                    result = java.util.Map.of("ok", false, "err", "timeout");
                                }
                                out.println(gson.toJson(result));
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
                                final boolean visualize = (cmd.visualize != null) ? cmd.visualize : true; // Default to true for backward compatibility

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

                                // Overlay (only show if visualize is true)
                                if (visualize) {
                                    final WorldPoint fStart = startWp;
                                    final WorldPoint fShownGoal = new WorldPoint(dbg.bestWx, dbg.bestWy, dbg.plane);                                                                
                                    final int fLen = full.size();
                                    clientThread.invokeLater(() -> {
                                        pathOverlay.setPath(full, fStart, fShownGoal);      
                                    });
                                }

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
                                        
                                        // Get canvas center coordinates
                                        final int centerX = c.getWidth() / 2;
                                        final int centerY = c.getHeight() / 2;
                                        
                                        long now = System.currentTimeMillis();
                                        
                                        // First, simulate mouse movement to center of canvas
                                        // This ensures the component "knows" the mouse is over it
                                        java.awt.event.MouseEvent moveEvent = new java.awt.event.MouseEvent(
                                                c, java.awt.event.MouseEvent.MOUSE_MOVED, now, 0,
                                                centerX, centerY, 0, false
                                        );
                                        c.dispatchEvent(moveEvent);
                                        
                                        // Small delay to ensure mouse move is processed
                                        try { Thread.sleep(10); } catch (InterruptedException ignored) {}
                                        
                                        // Now dispatch the scroll event
                                        // REVERSED: Positive amount = zoom IN, Negative amount = zoom OUT
                                        java.awt.event.MouseWheelEvent wheel = new java.awt.event.MouseWheelEvent(
                                                c, java.awt.event.MouseEvent.MOUSE_WHEEL, now + 20, 0,
                                                centerX, centerY, 0, false,
                                                java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL, Math.abs(amt),
                                                (amt > 0) ? -1 : 1  // REVERSED: positive amt = -1 wheelRotation (zoom in)
                                        );
                                        log.info("[IPC] dispatch MouseWheelEvent at ({},{}) rotations={} wheelRotation={}",
                                                centerX, centerY, Math.abs(amt), ((amt > 0) ? -1 : 1));
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

                            case "widget_exists": {
                                Integer widgetId = cmd.widget_id;
                                if (widgetId == null) {
                                    out.println("{\"ok\":false,\"err\":\"need widget_id\"}");
                                    break;
                                }
                                
                                final java.util.concurrent.CompletableFuture<java.util.Map<String,Object>> fut =
                                        new java.util.concurrent.CompletableFuture<>();
                                
                                clientThread.invokeLater(() -> {
                                    final java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
                                    try {
                                        // Try to get widget by ID (assuming it's a packed widget ID)
                                        Widget widget = client.getWidget(widgetId);
                                        boolean exists = (widget != null);
                                        boolean visible = false;
                                        
                                        if (widget != null) {
                                            // Check if widget is hidden
                                            boolean isHidden = widget.isHidden();
                                            
                                            // Check bounds and coordinates
                                            java.awt.Rectangle bounds = widget.getBounds();
                                            boolean hasValidBounds = (bounds != null && bounds.x >= 0 && bounds.y >= 0);
                                            
                                            // Widget is visible only if not hidden AND has valid bounds
                                            visible = !isHidden && hasValidBounds;
                                        }
                                        
                                        resp.put("ok", true);
                                        resp.put("exists", exists);
                                        resp.put("visible", visible);
                                        resp.put("widget_id", widgetId);
                                    } catch (Throwable t) {
                                        resp.put("ok", false);
                                        resp.put("err", "widget-exists-failed");
                                    } finally {
                                        fut.complete(resp);
                                    }
                                });
                                
                                java.util.Map<String,Object> result;
                                try {
                                    result = fut.get(150, java.util.concurrent.TimeUnit.MILLISECONDS);
                                } catch (Exception e) {
                                    result = java.util.Map.of("ok", false, "err", "timeout");
                                }
                                out.println(gson.toJson(result));
                                break;
                            }
                            
                            case "get_widget": {
                                Integer widgetId = cmd.widget_id;
                                if (widgetId == null) {
                                    out.println("{\"ok\":false,\"err\":\"need widget_id\"}");
                                    break;
                                }
                                
                                final java.util.concurrent.CompletableFuture<java.util.Map<String,Object>> fut =
                                        new java.util.concurrent.CompletableFuture<>();
                                
                                clientThread.invokeLater(() -> {
                                    final java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
                                    try {
                                        Widget widget = client.getWidget(widgetId);
                                        if (widget != null) {
                                            java.util.Map<String,Object> widgetData = new java.util.LinkedHashMap<>();
                                            widgetData.put("id", widgetId);
                                            
                                            // Get text safely
                                            try {
                                                String text = widget.getText();
                                                widgetData.put("text", text != null ? text : "");
                                            } catch (Exception ignored) {
                                                widgetData.put("text", "");
                                            }
                                            
                                            // Check visibility using bounds (like your existing code)
                                            boolean visible = false;
                                            java.awt.Rectangle bounds = null;
                                            try {
                                                bounds = widget.getBounds();
                                                visible = (bounds != null);
                                            } catch (Exception ignored) {}
                                            
                                            widgetData.put("visible", visible);
                                            
                                            // Get bounds if available
                                            if (bounds != null) {
                                                widgetData.put("bounds", java.util.Map.of(
                                                        "x", bounds.x,
                                                        "y", bounds.y,
                                                        "width", bounds.width,
                                                        "height", bounds.height
                                                ));
                                            }
                                            
                                            resp.put("ok", true);
                                            resp.put("widget", widgetData);
                                        } else {
                                            resp.put("ok", false);
                                            resp.put("err", "widget-not-found");
                                        }
                                    } catch (Throwable t) {
                                        resp.put("ok", false);
                                        resp.put("err", "get-widget-failed");
                                    } finally {
                                        fut.complete(resp);
                                    }
                                });
                                
                                java.util.Map<String,Object> result;
                                try {
                                    result = fut.get(150, java.util.concurrent.TimeUnit.MILLISECONDS);
                                } catch (Exception e) {
                                    result = java.util.Map.of("ok", false, "err", "timeout");
                                }
                                out.println(gson.toJson(result));
                                break;
                            }
                            
                            case "get_widget_info": {
                                Integer widgetId = cmd.widget_id;
                                if (widgetId == null) {
                                    out.println("{\"ok\":false,\"err\":\"need widget_id\"}");
                                    break;
                                }
                                
                                final java.util.concurrent.CompletableFuture<java.util.Map<String,Object>> fut =
                                        new java.util.concurrent.CompletableFuture<>();
                                
                                clientThread.invokeLater(() -> {
                                    final java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
                                    try {
                                        Widget widget = client.getWidget(widgetId);
                                        if (widget != null) {
                                            java.util.Map<String,Object> widgetData = new java.util.LinkedHashMap<>();
                                            widgetData.put("id", widgetId);
                                            
                            // Get text safely - try multiple methods for different widget types
                            String text = "";
                            try {
                                // Try basic getText() first
                                text = widget.getText();
                                
                                // If parent widget text is empty, check dynamic children first (most common case)
                                if (text == null || text.isEmpty()) {
                                    // Try dynamic children first (common for overlay text)
                                    Widget[] dynamicChildren = widget.getDynamicChildren();
                                    if (dynamicChildren != null) {
                                        for (Widget child : dynamicChildren) {
                                            if (child != null) {
                                                String childText = child.getText();
                                                if (childText != null && !childText.isEmpty() && !child.isHidden()) {
                                                    text = childText;
                                                    break;
                                                }
                                            }
                                        }
                                    }
                                    
                                    // If still empty, try static children
                                    if (text.isEmpty()) {
                                        Widget[] children = widget.getStaticChildren();
                                        if (children != null) {
                                            for (Widget child : children) {
                                                if (child != null) {
                                                    String childText = child.getText();
                                                    if (childText != null && !childText.isEmpty() && !child.isHidden()) {
                                                        text = childText;
                                                        break;
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    
                                    // If still empty, try getting text from parent widget
                                    if (text.isEmpty()) {
                                        Widget parent = widget.getParent();
                                        if (parent != null) {
                                            String parentText = parent.getText();
                                            if (parentText != null && !parentText.isEmpty() && !parent.isHidden()) {
                                                text = parentText;
                                            }
                                        }
                                    }
                                }
                                                
                                            } catch (Exception ignored) {
                                                text = "";
                                            }
                                            widgetData.put("text", text != null ? text : "");
                                            
                                            // Get sprite ID safely
                                            try {
                                                int spriteId = widget.getSpriteId();
                                                widgetData.put("spriteId", spriteId);
                                            } catch (Exception ignored) {
                                                widgetData.put("spriteId", -1);
                                            }
                                            
                                            // Get OnOpListener safely
                                            try {
                                                Object onOpListener = widget.getOnOpListener();
                                                widgetData.put("onOpListener", onOpListener);
                                            } catch (Exception ignored) {
                                                widgetData.put("onOpListener", null);
                                            }
                                            
                                            // Get text color safely
                                            try {
                                                int textColor = widget.getTextColor();
                                                widgetData.put("textColor", String.format("%x", textColor));
                                            } catch (Exception ignored) {
                                                widgetData.put("textColor", "");
                                            }
                                            
                                            // Get other important properties
                                            try {
                                                widgetData.put("isIf3", widget.isIf3());
                                                widgetData.put("hasListener", widget.hasListener());
                                                widgetData.put("hidden", widget.isHidden());
                                            } catch (Exception ignored) {
                                                widgetData.put("isIf3", false);
                                                widgetData.put("hasListener", false);
                                                widgetData.put("hidden", true);
                                            }
                                            
                                            // Check visibility properly: not hidden AND has valid bounds
                                            boolean visible = false;
                                            java.awt.Rectangle bounds = null;
                                            try {
                                                bounds = widget.getBounds();
                                                boolean isHidden = widget.isHidden();
                                                boolean hasValidBounds = (bounds != null && bounds.x >= 0 && bounds.y >= 0);
                                                visible = !isHidden && hasValidBounds;
                                            } catch (Exception ignored) {}
                                            
                                            widgetData.put("visible", visible);
                                            
                                            // Get bounds if available
                                            if (bounds != null) {
                                                widgetData.put("bounds", java.util.Map.of(
                                                        "x", bounds.x,
                                                        "y", bounds.y,
                                                        "width", bounds.width,
                                                        "height", bounds.height
                                                ));
                                            }
                                            
                                            // Determine section based on widget ID
                                            String section = "unknown";
                                            if (widgetId >= 44498948) {
                                                section = "character_design";
                                            } else if (widgetId >= 36569100 && widgetId <= 36569110) {
                                                section = "tutorial";
                                            }
                                            
                                            resp.put("ok", true);
                                            resp.put("widget", widgetData);
                                            resp.put("section", section);
                                        } else {
                                            resp.put("ok", false);
                                            resp.put("err", "widget-not-found");
                                        }
                                    } catch (Throwable t) {
                                        resp.put("ok", false);
                                        resp.put("err", "get-widget-info-failed");
                                    } finally {
                                        fut.complete(resp);
                                    }
                                });
                                
                                java.util.Map<String,Object> result;
                                try {
                                    result = fut.get(150, java.util.concurrent.TimeUnit.MILLISECONDS);
                                } catch (Exception e) {
                                    result = java.util.Map.of("ok", false, "err", "timeout");
                                }
                                out.println(gson.toJson(result));
                                break;
                            }

                            case "get_widget_children": {
                                Integer widgetId = cmd.widget_id;
                                if (widgetId == null) {
                                    out.println("{\"ok\":false,\"err\":\"need widget_id\"}");
                                    break;
                                }
                                
                                final java.util.concurrent.CompletableFuture<java.util.Map<String,Object>> fut =
                                        new java.util.concurrent.CompletableFuture<>();
                                
                                clientThread.invokeLater(() -> {
                                    final java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
                                    try {
                                        Widget parentWidget = client.getWidget(widgetId);
                                        if (parentWidget != null) {
                                            java.util.List<java.util.Map<String,Object>> children = new java.util.ArrayList<>();
                                            
                                            // Get all child widgets recursively
                                            collectChildWidgets(parentWidget, children);
                                            
                                            resp.put("ok", true);
                                            resp.put("parent_id", widgetId);
                                            resp.put("children", children);
                                            resp.put("count", children.size());
                                        } else {
                                            resp.put("ok", false);
                                            resp.put("err", "parent-widget-not-found");
                                        }
                                    } catch (Throwable t) {
                                        resp.put("ok", false);
                                        resp.put("err", "get-widget-children-failed");
                                    } finally {
                                        fut.complete(resp);
                                    }
                                });
                                
                                java.util.Map<String,Object> result;
                                try {
                                    result = fut.get(150, java.util.concurrent.TimeUnit.MILLISECONDS);
                                } catch (Exception e) {
                                    result = java.util.Map.of("ok", false, "err", "timeout");
                                }
                                out.println(gson.toJson(result));
                                break;
                            }

                            case "get_ge_prices": {
                                final java.util.concurrent.CompletableFuture<java.util.Map<String,Object>> fut =
                                        new java.util.concurrent.CompletableFuture<>();
                                
                                clientThread.invokeLater(() -> {
                                    final java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
                                    try {
                                        // Get Grand Exchange offers directly from client
                                        net.runelite.api.GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
                                        java.util.Map<String,Object> prices = new java.util.LinkedHashMap<>();
                                        
                                        if (offers != null) {
                                            for (net.runelite.api.GrandExchangeOffer offer : offers) {
                                                if (offer != null && offer.getItemId() > 0) {
                                                    // Get item composition for name
                                                    net.runelite.api.ItemComposition itemComp = client.getItemDefinition(offer.getItemId());
                                                    if (itemComp != null) {
                                                        String itemName = itemComp.getName();
                                                        int price = offer.getPrice();
                                                        if (price > 0) {
                                                            prices.put(itemName, price);
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        
                                        resp.put("ok", true);
                                        resp.put("prices", prices);
                                        resp.put("count", prices.size());
                                        
                                    } catch (Throwable t) {
                                        resp.put("ok", false);
                                        resp.put("err", "get-ge-prices-failed");
                                    } finally {
                                        fut.complete(resp);
                                    }
                                });
                                
                                java.util.Map<String,Object> result;
                                try {
                                    result = fut.get(150, java.util.concurrent.TimeUnit.MILLISECONDS);
                                } catch (Exception e) {
                                    result = java.util.Map.of("ok", false, "err", "timeout");
                                }
                                out.println(gson.toJson(result));
                                break;
                            }

                            case "get_bank_items": {
                                final java.util.concurrent.CompletableFuture<java.util.Map<String,Object>> fut =
                                        new java.util.concurrent.CompletableFuture<>();
                                
                                clientThread.invokeLater(() -> {
                                    final java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
                                    try {
                                        // Get bank items container (12.12 TABS_LINE0) - widget ID: 786444
                                        // Updated: Bank items moved from ITEMS (12.13) to TABS_LINE0 (12.12) after Runelite update
                                        Widget itemsContainer = client.getWidget(786444);
                                        if (itemsContainer != null) {
                                            java.util.List<java.util.Map<String,Object>> items = new java.util.ArrayList<>();
                                            collectChildWidgets(itemsContainer, items);
                                            
                                            resp.put("ok", true);
                                            resp.put("items", items);
                                        } else {
                                            resp.put("ok", false);
                                            resp.put("err", "bank-items-not-found");
                                        }
                                    } catch (Throwable t) {
                                        resp.put("ok", false);
                                        resp.put("err", "get-bank-items-failed");
                                    } finally {
                                        fut.complete(resp);
                                    }
                                });
                                
                                java.util.Map<String,Object> result;
                                try {
                                    result = fut.get(300, java.util.concurrent.TimeUnit.MILLISECONDS);
                                } catch (Exception e) {
                                    result = java.util.Map.of("ok", false, "err", "timeout");
                                }
                                out.println(gson.toJson(result));
                                break;
                            }

                            case "get_bank_tabs": {
                                final java.util.concurrent.CompletableFuture<java.util.Map<String,Object>> fut =
                                        new java.util.concurrent.CompletableFuture<>();
                                
                                clientThread.invokeLater(() -> {
                                    final java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
                                    try {
                                        // Get bank tabs container (12.11) - widget ID: 786443
                                        Widget tabsContainer = client.getWidget(786443);
                                        if (tabsContainer != null) {
                                            java.util.List<java.util.Map<String,Object>> tabs = new java.util.ArrayList<>();
                                            collectChildWidgets(tabsContainer, tabs);
                                            
                                            resp.put("ok", true);
                                            resp.put("tabs", tabs);
                                        } else {
                                            resp.put("ok", false);
                                            resp.put("err", "bank-tabs-not-found");
                                        }
                                    } catch (Throwable t) {
                                        resp.put("ok", false);
                                        resp.put("err", "get-bank-tabs-failed");
                                    } finally {
                                        fut.complete(resp);
                                    }
                                });
                                
                                java.util.Map<String,Object> result;
                                try {
                                    result = fut.get(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                                } catch (Exception e) {
                                    result = java.util.Map.of("ok", false, "err", "timeout");
                                }
                                out.println(gson.toJson(result));
                                break;
                            }

                            case "get_bank_quantity_buttons": {
                                final java.util.concurrent.CompletableFuture<java.util.Map<String,Object>> fut =
                                        new java.util.concurrent.CompletableFuture<>();
                                
                                clientThread.invokeLater(() -> {
                                    final java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
                                    try {
                                        java.util.List<java.util.Map<String,Object>> buttons = new java.util.ArrayList<>();
                                        
                                        // Get all quantity button containers
                                        int[] quantityContainers = {30, 32, 34, 36, 38}; // 1, 5, 10, X, All
                                        String[] buttonNames = {"quantity1", "quantity5", "quantity10", "quantityX", "quantityAll"};
                                        
                                        for (int i = 0; i < quantityContainers.length; i++) {
                                            // Calculate widget ID: 786433 + quantityContainers[i]
                                            Widget container = client.getWidget(786433 + quantityContainers[i]);
                                            if (container != null) {
                                                java.util.List<java.util.Map<String,Object>> containerButtons = new java.util.ArrayList<>();
                                                collectChildWidgets(container, containerButtons);
                                                
                                                java.util.Map<String,Object> buttonGroup = new java.util.LinkedHashMap<>();
                                                buttonGroup.put("name", buttonNames[i]);
                                                buttonGroup.put("container_id", quantityContainers[i]);
                                                buttonGroup.put("buttons", containerButtons);
                                                buttons.add(buttonGroup);
                                            }
                                        }
                                        
                                        resp.put("ok", true);
                                        resp.put("quantity_buttons", buttons);
                                    } catch (Throwable t) {
                                        resp.put("ok", false);
                                        resp.put("err", "get-bank-quantity-buttons-failed");
                                    } finally {
                                        fut.complete(resp);
                                    }
                                });
                                
                                java.util.Map<String,Object> result;
                                try {
                                    result = fut.get(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                                } catch (Exception e) {
                                    result = java.util.Map.of("ok", false, "err", "timeout");
                                }
                                out.println(gson.toJson(result));
                                break;
                            }

                            case "get_bank_deposit_buttons": {
                                final java.util.concurrent.CompletableFuture<java.util.Map<String,Object>> fut =
                                        new java.util.concurrent.CompletableFuture<>();
                                
                                clientThread.invokeLater(() -> {
                                    final java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
                                    try {
                                        java.util.List<java.util.Map<String,Object>> buttons = new java.util.ArrayList<>();
                                        
                                        // Get deposit inventory button (12.44) - widget ID: 786477
                                        Widget depositInv = client.getWidget(786477);
                                        if (depositInv != null) {
                                            java.util.Map<String,Object> buttonData = new java.util.LinkedHashMap<>();
                                            buttonData.put("name", "deposit_inventory");
                                            buttonData.put("id", depositInv.getId());
                                            buttonData.put("text", depositInv.getText() != null ? depositInv.getText() : "");
                                            
                                            java.awt.Rectangle bounds = depositInv.getBounds();
                                            if (bounds != null) {
                                                buttonData.put("bounds", java.util.Map.of(
                                                    "x", bounds.x, "y", bounds.y,
                                                    "width", bounds.width, "height", bounds.height
                                                ));
                                                buttonData.put("visible", true);
                                            } else {
                                                buttonData.put("visible", false);
                                            }
                                            buttons.add(buttonData);
                                        }
                                        
                                        // Get deposit equipment button (12.46) - widget ID: 786479
                                        Widget depositEquip = client.getWidget(786479);
                                        if (depositEquip != null) {
                                            java.util.Map<String,Object> buttonData = new java.util.LinkedHashMap<>();
                                            buttonData.put("name", "deposit_equipment");
                                            buttonData.put("id", depositEquip.getId());
                                            buttonData.put("text", depositEquip.getText() != null ? depositEquip.getText() : "");
                                            
                                            java.awt.Rectangle bounds = depositEquip.getBounds();
                                            if (bounds != null) {
                                                buttonData.put("bounds", java.util.Map.of(
                                                    "x", bounds.x, "y", bounds.y,
                                                    "width", bounds.width, "height", bounds.height
                                                ));
                                                buttonData.put("visible", true);
                                            } else {
                                                buttonData.put("visible", false);
                                            }
                                            buttons.add(buttonData);
                                        }
                                        
                                        resp.put("ok", true);
                                        resp.put("deposit_buttons", buttons);
                                    } catch (Throwable t) {
                                        resp.put("ok", false);
                                        resp.put("err", "get-bank-deposit-buttons-failed");
                                    } finally {
                                        fut.complete(resp);
                                    }
                                });
                                
                                java.util.Map<String,Object> result;
                                try {
                                    result = fut.get(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                                } catch (Exception e) {
                                    result = java.util.Map.of("ok", false, "err", "timeout");
                                }
                                out.println(gson.toJson(result));
                                break;
                            }

                            case "get_bank_note_toggle": {
                                final java.util.concurrent.CompletableFuture<java.util.Map<String,Object>> fut =
                                        new java.util.concurrent.CompletableFuture<>();
                                
                                clientThread.invokeLater(() -> {
                                    final java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
                                    try {
                                        // Get note toggle container (12.26) - widget ID: 786459
                                        Widget noteContainer = client.getWidget(786459);
                                        if (noteContainer != null) {
                                            java.util.List<java.util.Map<String,Object>> toggles = new java.util.ArrayList<>();
                                            collectChildWidgets(noteContainer, toggles);
                                            
                                            resp.put("ok", true);
                                            resp.put("note_toggles", toggles);
                                        } else {
                                            resp.put("ok", false);
                                            resp.put("err", "bank-note-toggle-not-found");
                                        }
                                    } catch (Throwable t) {
                                        resp.put("ok", false);
                                        resp.put("err", "get-bank-note-toggle-failed");
                                    } finally {
                                        fut.complete(resp);
                                    }
                                });
                                
                                java.util.Map<String,Object> result;
                                try {
                                    result = fut.get(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                                } catch (Exception e) {
                                    result = java.util.Map.of("ok", false, "err", "timeout");
                                }
                                out.println(gson.toJson(result));
                                break;
                            }

                            case "get_bank_search": {
                                final java.util.concurrent.CompletableFuture<java.util.Map<String,Object>> fut =
                                        new java.util.concurrent.CompletableFuture<>();
                                
                                clientThread.invokeLater(() -> {
                                    final java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
                                    try {
                                        java.util.List<java.util.Map<String,Object>> searchWidgets = new java.util.ArrayList<>();
                                        
                                        // Get search container (12.42) - widget ID: 786475
                                        Widget searchContainer = client.getWidget(786475);
                                        if (searchContainer != null) {
                                            java.util.Map<String,Object> searchData = new java.util.LinkedHashMap<>();
                                            searchData.put("name", "search_box");
                                            searchData.put("id", searchContainer.getId());
                                            searchData.put("text", searchContainer.getText() != null ? searchContainer.getText() : "");
                                            
                                            java.awt.Rectangle bounds = searchContainer.getBounds();
                                            if (bounds != null) {
                                                searchData.put("bounds", java.util.Map.of(
                                                    "x", bounds.x, "y", bounds.y,
                                                    "width", bounds.width, "height", bounds.height
                                                ));
                                                searchData.put("visible", true);
                                            } else {
                                                searchData.put("visible", false);
                                            }
                                            searchWidgets.add(searchData);
                                        }
                                        
                                        // Get search graphic (12.43) - widget ID: 786476
                                        Widget searchGraphic = client.getWidget(786476);
                                        if (searchGraphic != null) {
                                            java.util.Map<String,Object> graphicData = new java.util.LinkedHashMap<>();
                                            graphicData.put("name", "search_graphic");
                                            graphicData.put("id", searchGraphic.getId());
                                            
                                            java.awt.Rectangle bounds = searchGraphic.getBounds();
                                            if (bounds != null) {
                                                graphicData.put("bounds", java.util.Map.of(
                                                    "x", bounds.x, "y", bounds.y,
                                                    "width", bounds.width, "height", bounds.height
                                                ));
                                                graphicData.put("visible", true);
                                            } else {
                                                graphicData.put("visible", false);
                                            }
                                            searchWidgets.add(graphicData);
                                        }
                                        
                                        resp.put("ok", true);
                                        resp.put("search_widgets", searchWidgets);
                                    } catch (Throwable t) {
                                        resp.put("ok", false);
                                        resp.put("err", "get-bank-search-failed");
                                    } finally {
                                        fut.complete(resp);
                                    }
                                });
                                
                                java.util.Map<String,Object> result;
                                try {
                                    result = fut.get(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                                } catch (Exception e) {
                                    result = java.util.Map.of("ok", false, "err", "timeout");
                                }
                                out.println(gson.toJson(result));
                                break;
                            }

                            case "get_ge_widgets": {
                                final java.util.concurrent.CompletableFuture<java.util.Map<String,Object>> fut =
                                        new java.util.concurrent.CompletableFuture<>();
                                
                                clientThread.invokeLater(() -> {
                                    final java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
                                    try {
                                        // Get the main GE widget (465.1) - GeOffers.CONTENTS
                                        Widget mainGeWidget = client.getWidget(30474241); // 465.1
                                        if (mainGeWidget == null) {
                                            resp.put("ok", false);
                                            resp.put("err", "ge-not-open");
                                            fut.complete(resp);
                                            return;
                                        }
                                        
                                        java.util.List<java.util.Map<String,Object>> allWidgets = new java.util.ArrayList<>();
                                        
                                        // Use the existing collectChildWidgets method to get all children recursively
                                        collectChildWidgets(mainGeWidget, allWidgets);
                                        
                                        resp.put("ok", true);
                                        resp.put("widgets", allWidgets);
                                        resp.put("count", allWidgets.size());
                                        
                                    } catch (Throwable t) {
                                        resp.put("ok", false);
                                        resp.put("err", "get-ge-widgets-failed");
                                    } finally {
                                        fut.complete(resp);
                                    }
                                });
                                
                                java.util.Map<String,Object> result;
                                try {
                                    result = fut.get(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                                } catch (Exception e) {
                                    result = java.util.Map.of("ok", false, "err", "timeout");
                                }
                                out.println(gson.toJson(result));
                                break;
                            }

                            case "get_ge_offers": {
                                final java.util.concurrent.CompletableFuture<java.util.Map<String,Object>> fut =
                                        new java.util.concurrent.CompletableFuture<>();
                                
                                clientThread.invokeLater(() -> {
                                    final java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
                                    try {
                                        // Get the main GE widget (465.1) - GeOffers.CONTENTS
                                        Widget mainGeWidget = client.getWidget(30474241); // 465.1
                                        if (mainGeWidget == null) {
                                            resp.put("ok", false);
                                            resp.put("err", "ge-not-open");
                                            fut.complete(resp);
                                            return;
                                        }
                                        
                                        java.util.Map<String, java.util.List<java.util.Map<String,Object>>> indexOffers = new java.util.LinkedHashMap<>();
                                        
                                        // Define INDEX parent widget IDs and their ranges
                                        int[] indexParentIds = {(465 << 16) | 7, (465 << 16) | 8, (465 << 16) | 9, (465 << 16) | 10, (465 << 16) | 11, (465 << 16) | 12, (465 << 16) | 13, (465 << 16) | 14};
                                        String[] indexNames = {"INDEX_0", "INDEX_1", "INDEX_2", "INDEX_3", "INDEX_4", "INDEX_5", "INDEX_6", "INDEX_7"};
                                        
                                        // Initialize lists for each INDEX
                                        for (int i = 0; i < indexParentIds.length; i++) {
                                            indexOffers.put(indexNames[i], new java.util.ArrayList<>());
                                        }
                                        
                                        // Process each INDEX parent widget
                                        for (int i = 0; i < indexParentIds.length; i++) {
                                            Widget indexWidget = client.getWidget(indexParentIds[i]);
                                            if (indexWidget != null) {
                                                // Collect all nested children under this INDEX widget
                                                java.util.List<Widget> indexChildren = new java.util.ArrayList<>();
                                                getAllNestedChildren(indexWidget, indexChildren);
                                                
                                                // Process each child widget
                                                for (Widget child : indexChildren) {
                                                    if (child != null) {
                                                        // Check visibility: not hidden AND has valid bounds
                                                        boolean visible = false;
                                                        java.awt.Rectangle bounds = null;
                                                        try {
                                                            bounds = child.getBounds();
                                                            boolean isHidden = child.isHidden();
                                                            boolean hasValidBounds = (bounds != null && bounds.x >= 0 && bounds.y >= 0);
                                                            visible = !isHidden && hasValidBounds;
                                                        } catch (Exception ignored) {}
                                                        
                                                        // Only process visible widgets
                                                        if (visible) {
                                                            int widgetId = child.getId();
                                                            
                                                            java.util.Map<String,Object> offerData = new java.util.LinkedHashMap<>();
                                                            offerData.put("id", widgetId);
                                                            offerData.put("id_hex", String.format("0x%08X", widgetId));
                                                            
                                                            // Extract group, child, and grandchild from widget ID
                                                            int group = (widgetId >> 16) & 0xFFFF;
                                                            int childIndex = (widgetId >> 8) & 0xFF;
                                                            int grandchildIndex = widgetId & 0xFF;
                                                            
                                                            offerData.put("group", group);
                                                            offerData.put("child_index", childIndex);
                                                            offerData.put("grandchild_index", grandchildIndex);
                                                            
                                                            // Get text safely
                                                            try {
                                                                String text = child.getText();
                                                                offerData.put("text", text != null ? text : "");
                                                            } catch (Exception ignored) {
                                                                offerData.put("text", "");
                                                            }
                                                            
                                                            // Get bounds safely (we already have bounds from visibility check)
                                                            if (bounds != null) {
                                                                offerData.put("bounds", java.util.Map.of(
                                                                    "x", bounds.x, "y", bounds.y,
                                                                    "width", bounds.width, "height", bounds.height
                                                                ));
                                                            }
                                                            
                                                            // Get additional widget properties
                                                            try {
                                                                offerData.put("hasListener", child.hasListener());
                                                                offerData.put("spriteId", child.getSpriteId());
                                                                offerData.put("itemId", child.getItemId());
                                                                offerData.put("itemQuantity", child.getItemQuantity());
                                                            } catch (Exception ignored) {}
                                                            
                                                            // Add to the appropriate INDEX list
                                                            indexOffers.get(indexNames[i]).add(offerData);
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        
                                        resp.put("ok", true);
                                        resp.put("index_offers", indexOffers);
                                        
                                        // Also provide a flat list for backward compatibility
                                        java.util.List<java.util.Map<String,Object>> allOffers = new java.util.ArrayList<>();
                                        for (java.util.List<java.util.Map<String,Object>> offers : indexOffers.values()) {
                                            allOffers.addAll(offers);
                                        }
                                        resp.put("offers", allOffers);
                                        
                                    } catch (Throwable t) {
                                        resp.put("ok", false);
                                        resp.put("err", "get-ge-offers-failed");
                                    } finally {
                                        fut.complete(resp);
                                    }
                                });
                                
                                java.util.Map<String,Object> result;
                                try {
                                    result = fut.get(300, java.util.concurrent.TimeUnit.MILLISECONDS);
                                } catch (Exception e) {
                                    result = java.util.Map.of("ok", false, "err", "timeout");
                                }
                                out.println(gson.toJson(result));
                                break;
                            }

                            case "get_ge_setup": {
                                final java.util.concurrent.CompletableFuture<java.util.Map<String,Object>> fut =
                                        new java.util.concurrent.CompletableFuture<>();
                                
                                clientThread.invokeLater(() -> {
                                    final java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
                                    try {
                                        // Get the main GE widget (465.1) - GeOffers.CONTENTS
                                        Widget mainGeWidget = client.getWidget(30474241); // 465.1
                                        if (mainGeWidget == null) {
                                            resp.put("ok", false);
                                            resp.put("err", "ge-not-open");
                                            fut.complete(resp);
                                            return;
                                        }
                                        
                                        java.util.List<java.util.Map<String,Object>> setup = new java.util.ArrayList<>();
                                        
                                        // Get all child widgets and filter for setup widgets (465.26.*)
                                        java.util.List<Widget> allChildren = new java.util.ArrayList<>();
                                        getAllNestedChildren(mainGeWidget, allChildren);
                                        
                                        for (Widget child : allChildren) {
                                            if (child != null) {
                                                int widgetId = child.getId();
                                                // Check for setup widgets (30474266-30474324 range for 465.26[0-58])
                                                if (30474266 <= widgetId && widgetId <= 30474324) {
                                                    java.util.Map<String,Object> setupData = new java.util.LinkedHashMap<>();
                                                    setupData.put("id", widgetId);
                                                    setupData.put("index", widgetId - 30474266); // Calculate index
                                                    
                                                    // Get text safely
                                                    try {
                                                        String text = child.getText();
                                                        setupData.put("text", text != null ? text : "");
                                                    } catch (Exception ignored) {
                                                        setupData.put("text", "");
                                                    }
                                                    
                                                    // Get bounds safely
                                                    try {
                                                        java.awt.Rectangle bounds = child.getBounds();
                                                        if (bounds != null) {
                                                            setupData.put("bounds", java.util.Map.of(
                                                                "x", bounds.x, "y", bounds.y,
                                                                "width", bounds.width, "height", bounds.height
                                                            ));
                                                        }
                                                    } catch (Exception ignored) {}
                                                    
                                                    setup.add(setupData);
                                                }
                                            }
                                        }
                                        
                                        resp.put("ok", true);
                                        resp.put("setup", setup);
                                    } catch (Throwable t) {
                                        resp.put("ok", false);
                                        resp.put("err", "get-ge-setup-failed");
                                    } finally {
                                        fut.complete(resp);
                                    }
                                });
                                
                                java.util.Map<String,Object> result;
                                try {
                                    result = fut.get(300, java.util.concurrent.TimeUnit.MILLISECONDS);
                                } catch (Exception e) {
                                    result = java.util.Map.of("ok", false, "err", "timeout");
                                }
                                out.println(gson.toJson(result));
                                break;
                            }

                            case "get_ge_confirm": {
                                final java.util.concurrent.CompletableFuture<java.util.Map<String,Object>> fut =
                                        new java.util.concurrent.CompletableFuture<>();
                                
                                clientThread.invokeLater(() -> {
                                    final java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
                                    try {
                                        // Get the main GE widget (465.1) - GeOffers.CONTENTS
                                        Widget mainGeWidget = client.getWidget(30474241); // 465.1
                                        if (mainGeWidget == null) {
                                            resp.put("ok", false);
                                            resp.put("err", "ge-not-open");
                                            fut.complete(resp);
                                            return;
                                        }
                                        
                                        java.util.List<java.util.Map<String,Object>> confirm = new java.util.ArrayList<>();
                                        
                                        // Get all child widgets and filter for confirm widgets (465.30.*)
                                        java.util.List<Widget> allChildren = new java.util.ArrayList<>();
                                        getAllNestedChildren(mainGeWidget, allChildren);
                                        
                                        for (Widget child : allChildren) {
                                            if (child != null) {
                                                int widgetId = child.getId();
                                                // Check for confirm widgets (30474270-30474278 range for 465.30[0-8])
                                                if (30474270 <= widgetId && widgetId <= 30474278) {
                                                    java.util.Map<String,Object> confirmData = new java.util.LinkedHashMap<>();
                                                    confirmData.put("id", widgetId);
                                                    confirmData.put("index", widgetId - 30474270); // Calculate index
                                                    
                                                    // Get text safely
                                                    try {
                                                        String text = child.getText();
                                                        confirmData.put("text", text != null ? text : "");
                                                    } catch (Exception ignored) {
                                                        confirmData.put("text", "");
                                                    }
                                                    
                                                    // Get bounds safely
                                                    try {
                                                        java.awt.Rectangle bounds = child.getBounds();
                                                        if (bounds != null) {
                                                            confirmData.put("bounds", java.util.Map.of(
                                                                "x", bounds.x, "y", bounds.y,
                                                                "width", bounds.width, "height", bounds.height
                                                            ));
                                                        }
                                                    } catch (Exception ignored) {}
                                                    
                                                    confirm.add(confirmData);
                                                }
                                            }
                                        }
                                        
                                        resp.put("ok", true);
                                        resp.put("confirm", confirm);
                                    } catch (Throwable t) {
                                        resp.put("ok", false);
                                        resp.put("err", "get-ge-confirm-failed");
                                    } finally {
                                        fut.complete(resp);
                                    }
                                });
                                
                                java.util.Map<String,Object> result;
                                try {
                                    result = fut.get(300, java.util.concurrent.TimeUnit.MILLISECONDS);
                                } catch (Exception e) {
                                    result = java.util.Map.of("ok", false, "err", "timeout");
                                }
                                out.println(gson.toJson(result));
                                break;
                            }

                            case "get_ge_buttons": {
                                final java.util.concurrent.CompletableFuture<java.util.Map<String,Object>> fut =
                                        new java.util.concurrent.CompletableFuture<>();
                                
                                clientThread.invokeLater(() -> {
                                    final java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
                                    try {
                                        java.util.List<java.util.Map<String,Object>> buttons = new java.util.ArrayList<>();
                                        
                                        // Get main GE buttons
                                        int[] buttonIds = {(465 << 16) | 4, (465 << 16) | 5, (465 << 16) | 6}; // BACK, INDEX, COLLECTALL
                                        String[] buttonNames = {"back", "index", "collect_all"};
                                        
                                        for (int i = 0; i < buttonIds.length; i++) {
                                            Widget buttonWidget = client.getWidget(buttonIds[i]);
                                            if (buttonWidget != null) {
                                                java.util.Map<String,Object> buttonData = new java.util.LinkedHashMap<>();
                                                buttonData.put("name", buttonNames[i]);
                                                buttonData.put("id", buttonWidget.getId());
                                                
                                                // Get text safely
                                                try {
                                                    String text = buttonWidget.getText();
                                                    buttonData.put("text", text != null ? text : "");
                                                } catch (Exception ignored) {
                                                    buttonData.put("text", "");
                                                }
                                                
                                                // Get bounds safely
                                                try {
                                                    java.awt.Rectangle bounds = buttonWidget.getBounds();
                                                    if (bounds != null) {
                                                        buttonData.put("bounds", java.util.Map.of(
                                                            "x", bounds.x, "y", bounds.y,
                                                            "width", bounds.width, "height", bounds.height
                                                        ));
                                                    }
                                                } catch (Exception ignored) {}
                                                
                                                buttons.add(buttonData);
                                            }
                                        }
                                        
                                        resp.put("ok", true);
                                        resp.put("buttons", buttons);
                                    } catch (Throwable t) {
                                        resp.put("ok", false);
                                        resp.put("err", "get-ge-buttons-failed");
                                    } finally {
                                        fut.complete(resp);
                                    }
                                });
                                
                                java.util.Map<String,Object> result;
                                try {
                                    result = fut.get(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                                } catch (Exception e) {
                                    result = java.util.Map.of("ok", false, "err", "timeout");
                                }
                                out.println(gson.toJson(result));
                                break;
                            }


                            case "door_state": {
                                Integer doorX = cmd.door_x;
                                Integer doorY = cmd.door_y;
                                Integer doorP = cmd.door_p;
                                
                                if (doorX == null || doorY == null || doorP == null) {
                                    out.println("{\"ok\":false,\"err\":\"need door_x, door_y, door_p\"}");
                                    break;
                                }
                                
                                final java.util.concurrent.CompletableFuture<java.util.Map<String,Object>> fut =
                                        new java.util.concurrent.CompletableFuture<>();
                                
                                clientThread.invokeLater(() -> {
                                    final java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
                                    try {
                                        final Scene scene = client.getScene();
                                        if (scene == null) {
                                            resp.put("ok", false);
                                            resp.put("err", "no-scene");
                                            fut.complete(resp);
                                            return;
                                        }
                                        
                                        final int baseX = client.getBaseX();
                                        final int baseY = client.getBaseY();
                                        final int lx = doorX - baseX;
                                        final int ly = doorY - baseY;
                                        
                                        if (lx < 0 || lx >= 104 || ly < 0 || ly >= 104) {
                                            resp.put("ok", false);
                                            resp.put("err", "tile-out-of-scene");
                                            fut.complete(resp);
                                            return;
                                        }
                                        
                                        final Tile[][][] tilesArr = scene.getTiles();
                                        if (tilesArr == null || doorP < 0 || doorP >= tilesArr.length || tilesArr[doorP] == null) {
                                            resp.put("ok", false);
                                            resp.put("err", "invalid-plane");
                                            fut.complete(resp);
                                            return;
                                        }
                                        
                                        final Tile tile = tilesArr[doorP][lx][ly];
                                        final WallObject wobj = (tile != null) ? tile.getWallObject() : null;
                                        
                                        if (wobj == null) {
                                            resp.put("ok", true);
                                            resp.put("wall_object", null);
                                        } else {
                                            final ObjectComposition comp = client.getObjectDefinition(wobj.getId());
                                            
                                            java.util.Map<String,Object> wallObject = new java.util.LinkedHashMap<>();
                                            wallObject.put("id", wobj.getId());
                                            wallObject.put("orientationA", wobj.getOrientationA());
                                            wallObject.put("orientationB", wobj.getOrientationB());
                                            
                                            if (comp != null) {
                                                wallObject.put("name", comp.getName());
                                            }
                                            
                                            // Get convex hull bounds
                                            try {
                                                final java.awt.Shape hull = wobj.getConvexHull();
                                                if (hull != null) {
                                                    final java.awt.Rectangle rb = hull.getBounds();
                                                    if (rb != null) {
                                                        wallObject.put("bounds", java.util.Map.of(
                                                                "x", rb.x, "y", rb.y, "width", rb.width, "height", rb.height
                                                        ));
                                                    }
                                                }
                                            } catch (Exception ignored) {}
                                            
                                            resp.put("ok", true);
                                            resp.put("wall_object", wallObject);
                                        }
                                        
                                    } catch (Throwable t) {
                                        resp.put("ok", false);
                                        resp.put("err", "door-state-failed");
                                    } finally {
                                        fut.complete(resp);
                                    }
                                });
                                
                                java.util.Map<String,Object> result;
                                try {
                                    result = fut.get(150, java.util.concurrent.TimeUnit.MILLISECONDS);
                                } catch (Exception e) {
                                    result = java.util.Map.of("ok", false, "err", "timeout");
                                }
                                out.println(gson.toJson(result));
                                break;
                            }

                            case "get_chat_widgets": {
                                final java.util.concurrent.CompletableFuture<java.util.Map<String,Object>> fut =
                                        new java.util.concurrent.CompletableFuture<>();
                                
                                clientThread.invokeLater(() -> {
                                    final java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
                                    try {
                                        resp.put("ok", true);
                                        
                                        // ChatLeft widgets (NPC dialogue)
                                        java.util.Map<String,Object> chatLeft = new java.util.LinkedHashMap<>();
                                        
                                        // ChatLeft.NAME (15138820)
                                        Widget wChatName = client.getWidget(15138820);
                                        java.util.Map<String,Object> nameInfo = new java.util.LinkedHashMap<>();
                                        nameInfo.put("id", 15138820);
                                        nameInfo.put("exists", wChatName != null && !wChatName.isHidden());
                                        if (wChatName != null && !wChatName.isHidden()) {
                                            nameInfo.put("text", wChatName.getText());
                                            java.awt.Rectangle bounds = wChatName.getBounds();
                                            if (bounds != null) {
                                                nameInfo.put("bounds", java.util.Map.of(
                                                        "x", bounds.x,
                                                        "y", bounds.y,
                                                        "width", bounds.width,
                                                        "height", bounds.height
                                                ));
                                            } else {
                                                nameInfo.put("bounds", null);
                                            }
                                        } else {
                                            nameInfo.put("text", null);
                                            nameInfo.put("bounds", null);
                                        }
                                        chatLeft.put("name", nameInfo);
                                        
                                        // ChatLeft.CONTINUE (15138821)
                                        Widget wChatContinue = client.getWidget(15138821);
                                        java.util.Map<String,Object> continueInfo = new java.util.LinkedHashMap<>();
                                        continueInfo.put("id", 15138821);
                                        continueInfo.put("exists", wChatContinue != null && !wChatContinue.isHidden());
                                        if (wChatContinue != null && !wChatContinue.isHidden()) {
                                            java.awt.Rectangle bounds = wChatContinue.getBounds();
                                            if (bounds != null) {
                                                continueInfo.put("bounds", java.util.Map.of(
                                                        "x", bounds.x,
                                                        "y", bounds.y,
                                                        "width", bounds.width,
                                                        "height", bounds.height
                                                ));
                                                
                                                // Calculate center point for easy clicking
                                                java.util.Map<String,Object> center = new java.util.LinkedHashMap<>();
                                                center.put("x", bounds.x + bounds.width / 2);
                                                center.put("y", bounds.y + bounds.height / 2);
                                                continueInfo.put("center", center);
                                            } else {
                                                continueInfo.put("bounds", null);
                                                continueInfo.put("center", null);
                                            }
                                            continueInfo.put("hasListener", wChatContinue.hasListener());
                                            continueInfo.put("text", wChatContinue.getText());
                                            continueInfo.put("name", wChatContinue.getName());
                                        } else {
                                            continueInfo.put("bounds", null);
                                            continueInfo.put("hasListener", false);
                                            continueInfo.put("text", null);
                                            continueInfo.put("name", null);
                                            continueInfo.put("center", null);
                                        }
                                        chatLeft.put("continue", continueInfo);
                                        
                                        // ChatLeft.TEXT (15138822)
                                        Widget wChatText = client.getWidget(15138822);
                                        java.util.Map<String,Object> textInfo = new java.util.LinkedHashMap<>();
                                        textInfo.put("id", 15138822);
                                        textInfo.put("exists", wChatText != null && !wChatText.isHidden());
                                        if (wChatText != null && !wChatText.isHidden()) {
                                            textInfo.put("text", wChatText.getText());
                                            java.awt.Rectangle bounds = wChatText.getBounds();
                                            if (bounds != null) {
                                                textInfo.put("bounds", java.util.Map.of(
                                                        "x", bounds.x,
                                                        "y", bounds.y,
                                                        "width", bounds.width,
                                                        "height", bounds.height
                                                ));
                                            } else {
                                                textInfo.put("bounds", null);
                                            }
                                        } else {
                                            textInfo.put("text", null);
                                            textInfo.put("bounds", null);
                                        }
                                        chatLeft.put("text", textInfo);
                                        
                                        resp.put("chatLeft", chatLeft);
                                        
                                        // ChatMenu widgets (dialogue options)
                                        java.util.Map<String,Object> chatMenu = new java.util.LinkedHashMap<>();
                                        
                                        // Chatmenu.OPTIONS (14352385)
                                        Widget wOptions = client.getWidget(14352385);
                                        java.util.Map<String,Object> optionsInfo = new java.util.LinkedHashMap<>();
                                        optionsInfo.put("id", 14352385);
                                        optionsInfo.put("exists", wOptions != null && !wOptions.isHidden());
                                        
                                        java.util.List<String> optionTexts = new java.util.ArrayList<>();
                                        if (wOptions != null && !wOptions.isHidden() && wOptions.getChildren() != null) {
                                            java.awt.Rectangle bounds = wOptions.getBounds();
                                            if (bounds != null) {
                                                optionsInfo.put("bounds", java.util.Map.of(
                                                        "x", bounds.x,
                                                        "y", bounds.y,
                                                        "width", bounds.width,
                                                        "height", bounds.height
                                                ));
                                            } else {
                                                optionsInfo.put("bounds", null);
                                            }
                                            for (Widget child : wOptions.getChildren()) {
                                                if (child != null && !child.isHidden()) {
                                                    String text = child.getText();
                                                    if (text != null && !text.trim().isEmpty()) {
                                                        optionTexts.add(text);
                                                    }
                                                }
                                            }
                                        } else {
                                            optionsInfo.put("bounds", null);
                                        }
                                        optionsInfo.put("texts", optionTexts);
                                        chatMenu.put("options", optionsInfo);
                                        
                                        resp.put("chatMenu", chatMenu);
                                        
                                        // ChatRight widgets (player dialogue)
                                        java.util.Map<String,Object> chatRight = new java.util.LinkedHashMap<>();
                                        
                                        // Player Name (14221316)
                                        Widget wPlayerName = client.getWidget(14221316);
                                        java.util.Map<String,Object> playerNameInfo = new java.util.LinkedHashMap<>();
                                        playerNameInfo.put("id", 14221316);
                                        playerNameInfo.put("exists", wPlayerName != null && !wPlayerName.isHidden());
                                        if (wPlayerName != null && !wPlayerName.isHidden()) {
                                            playerNameInfo.put("text", wPlayerName.getText());
                                            java.awt.Rectangle bounds = wPlayerName.getBounds();
                                            if (bounds != null) {
                                                playerNameInfo.put("bounds", java.util.Map.of(
                                                        "x", bounds.x,
                                                        "y", bounds.y,
                                                        "width", bounds.width,
                                                        "height", bounds.height
                                                ));
                                            } else {
                                                playerNameInfo.put("bounds", null);
                                            }
                                        } else {
                                            playerNameInfo.put("text", null);
                                            playerNameInfo.put("bounds", null);
                                        }
                                        chatRight.put("name", playerNameInfo);
                                        
                                        // Player Continue (14221317)
                                        Widget wPlayerContinue = client.getWidget(14221317);
                                        java.util.Map<String,Object> playerContinueInfo = new java.util.LinkedHashMap<>();
                                        playerContinueInfo.put("id", 14221317);
                                        playerContinueInfo.put("exists", wPlayerContinue != null && !wPlayerContinue.isHidden());
                                        if (wPlayerContinue != null && !wPlayerContinue.isHidden()) {
                                            java.awt.Rectangle bounds = wPlayerContinue.getBounds();
                                            if (bounds != null) {
                                                playerContinueInfo.put("bounds", java.util.Map.of(
                                                        "x", bounds.x,
                                                        "y", bounds.y,
                                                        "width", bounds.width,
                                                        "height", bounds.height
                                                ));
                                                
                                                // Calculate center point for easy clicking
                                                java.util.Map<String,Object> center = new java.util.LinkedHashMap<>();
                                                center.put("x", bounds.x + bounds.width / 2);
                                                center.put("y", bounds.y + bounds.height / 2);
                                                playerContinueInfo.put("center", center);
                                            } else {
                                                playerContinueInfo.put("bounds", null);
                                                playerContinueInfo.put("center", null);
                                            }
                                            playerContinueInfo.put("hasListener", wPlayerContinue.hasListener());
                                            playerContinueInfo.put("text", wPlayerContinue.getText());
                                            playerContinueInfo.put("name", wPlayerContinue.getName());
                                        } else {
                                            playerContinueInfo.put("bounds", null);
                                            playerContinueInfo.put("hasListener", false);
                                            playerContinueInfo.put("text", null);
                                            playerContinueInfo.put("name", null);
                                            playerContinueInfo.put("center", null);
                                        }
                                        chatRight.put("continue", playerContinueInfo);
                                        
                                        // Player Text (14221318)
                                        Widget wPlayerText = client.getWidget(14221318);
                                        java.util.Map<String,Object> playerTextInfo = new java.util.LinkedHashMap<>();
                                        playerTextInfo.put("id", 14221318);
                                        playerTextInfo.put("exists", wPlayerText != null && !wPlayerText.isHidden());
                                        if (wPlayerText != null && !wPlayerText.isHidden()) {
                                            playerTextInfo.put("text", wPlayerText.getText());
                                            java.awt.Rectangle bounds = wPlayerText.getBounds();
                                            if (bounds != null) {
                                                playerTextInfo.put("bounds", java.util.Map.of(
                                                        "x", bounds.x,
                                                        "y", bounds.y,
                                                        "width", bounds.width,
                                                        "height", bounds.height
                                                ));
                                            } else {
                                                playerTextInfo.put("bounds", null);
                                            }
                                        } else {
                                            playerTextInfo.put("text", null);
                                            playerTextInfo.put("bounds", null);
                                        }
                                        chatRight.put("text", playerTextInfo);
                                        
                                        resp.put("chatRight", chatRight);
                                        
                                    } catch (Exception e) {
                                        resp.put("ok", false);
                                        resp.put("error", e.getMessage());
                                    } finally {
                                        fut.complete(resp);
                                    }
                                });
                                
                                java.util.Map<String,Object> result;
                                try {
                                    result = fut.get(150, java.util.concurrent.TimeUnit.MILLISECONDS);
                                } catch (Exception e) {
                                    result = java.util.Map.of("ok", false, "err", "timeout");
                                }
                                out.println(gson.toJson(result));
                                break;
                            }

                            case "get_player": {
                                final java.util.concurrent.CompletableFuture<java.util.Map<String,Object>> fut =
                                        new java.util.concurrent.CompletableFuture<>();
                                
                                clientThread.invokeLater(() -> {
                                    final java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
                                    try {
                                        final Player localPlayer = client.getLocalPlayer();
                                        if (localPlayer == null) {
                                            resp.put("ok", false);
                                            resp.put("err", "no-local-player");
                                            fut.complete(resp);
                                            return;
                                        }
                                        
                                        final java.util.Map<String,Object> player = new java.util.LinkedHashMap<>();
                                        
                                        // Basic player info
                                        player.put("name", localPlayer.getName());
                                        player.put("combatLevel", localPlayer.getCombatLevel());
                                        player.put("animation", localPlayer.getAnimation());
                                        
                                        // Orientation (facing direction in degrees, 0-2047)
                                        player.put("orientation", localPlayer.getOrientation());
                                        
                                        // World location
                                        final WorldPoint worldLocation = localPlayer.getWorldLocation();
                                        if (worldLocation != null) {
                                            player.put("worldX", worldLocation.getX());
                                            player.put("worldY", worldLocation.getY());
                                            player.put("plane", worldLocation.getPlane());
                                        }
                                        
                                        // Canvas location
                                        final LocalPoint localLocation = localPlayer.getLocalLocation();
                                        if (localLocation != null) {
                                            final net.runelite.api.Point canvasLocation = Perspective.localToCanvas(client, localLocation, localPlayer.getWorldLocation().getPlane());
                                            if (canvasLocation != null) {
                                                player.put("canvasX", canvasLocation.getX());
                                                player.put("canvasY", canvasLocation.getY());
                                            }
                                        }
                                        
                                        // Health/status
                                        player.put("healthRatio", localPlayer.getHealthRatio());
                                        player.put("healthScale", localPlayer.getHealthScale());
                                        
                                        // Run energy
                                        player.put("runEnergy", client.getEnergy());
                                        
                                        // Movement state using pose animation
                                        // 824 = running, 819 = walking, 808 = standing still
                                        try {
                                            final int poseAnimation = localPlayer.getPoseAnimation();
                                            player.put("poseAnimation", poseAnimation);
                                            player.put("isRunning", (poseAnimation == 824));
                                            player.put("isWalking", (poseAnimation == 819));
                                            player.put("isStanding", (poseAnimation == 808));
                                        } catch (Exception e) {
                                            // Fallback: assume standing
                                            player.put("poseAnimation", -1);
                                            player.put("isRunning", false);
                                            player.put("isWalking", false);
                                            player.put("isStanding", true);
                                        }
                                        
                                        // Combat status
                                        player.put("isInteracting", localPlayer.isInteracting());
                                        player.put("combatLevel", localPlayer.getCombatLevel());
                                        
                                        // Get interacting target info
                                        final Actor interacting = localPlayer.getInteracting();
                                        if (interacting != null) {
                                            final java.util.Map<String,Object> target = new java.util.LinkedHashMap<>();
                                            target.put("name", interacting.getName());
                                            
                                            // Get ID based on actor type
                                            if (interacting instanceof NPC) {
                                                target.put("id", ((NPC) interacting).getId());
                                                target.put("type", "NPC");
                                            } else if (interacting instanceof Player) {
                                                target.put("id", ((Player) interacting).getId());
                                                target.put("type", "Player");
                                            } else {
                                                target.put("id", -1);
                                                target.put("type", "Unknown");
                                            }
                                            
                                            target.put("worldX", interacting.getWorldLocation().getX());
                                            target.put("worldY", interacting.getWorldLocation().getY());
                                            target.put("worldP", interacting.getWorldLocation().getPlane());
                                            target.put("healthRatio", interacting.getHealthRatio());
                                            target.put("healthScale", interacting.getHealthScale());
                                            target.put("combatLevel", interacting.getCombatLevel());
                                            player.put("interactingTarget", target);
                                        } else {
                                            player.put("interactingTarget", null);
                                        }
                                        
                                        // Skills
                                        final java.util.Map<String,Object> skills = new java.util.LinkedHashMap<>();
                                        try {
                                            // Get all skill levels and XP
                                            for (final Skill skill : Skill.values()) {
                                                if (skill != Skill.OVERALL) { // Skip overall skill
                                                    final java.util.Map<String,Object> skillData = new java.util.LinkedHashMap<>();
                                                    skillData.put("level", client.getRealSkillLevel(skill));
                                                    skillData.put("boostedLevel", client.getBoostedSkillLevel(skill));
                                                    skillData.put("xp", client.getSkillExperience(skill));
                                                    skills.put(skill.getName().toLowerCase(), skillData);
                                                }
                                            }
                                        } catch (Exception e) {
                                            // If skill data fails, continue without it
                                        }
                                        player.put("skills", skills);
                                        
                                        // Game objects on player's tile
                                        final java.util.List<java.util.Map<String,Object>> tileObjects = new java.util.ArrayList<>();
                                        if (worldLocation != null) {
                                            final Scene scene = client.getScene();
                                            if (scene != null) {
                                                final int baseX = client.getBaseX();
                                                final int baseY = client.getBaseY();
                                                final int localX = worldLocation.getX() - baseX;
                                                final int localY = worldLocation.getY() - baseY;
                                                
                                                if (localX >= 0 && localX < 104 && localY >= 0 && localY < 104) {
                                                    final Tile tile = scene.getTiles()[worldLocation.getPlane()][localX][localY];
                                                    if (tile != null) {
                                                        // Ground object
                                                        final GroundObject groundObject = tile.getGroundObject();
                                                        if (groundObject != null) {
                                                            final java.util.Map<String,Object> obj = new java.util.LinkedHashMap<>();
                                                            obj.put("type", "GROUND");
                                                            obj.put("id", groundObject.getId());
                                                            final ObjectComposition comp = client.getObjectDefinition(groundObject.getId());
                                                            if (comp != null) {
                                                                obj.put("name", comp.getName());
                                                                obj.put("actions", comp.getActions());
                                                            }
                                                            tileObjects.add(obj);
                                                        }
                                                        
                                                        // Wall objects
                                                        final WallObject wallObject = tile.getWallObject();
                                                        if (wallObject != null) {
                                                            final java.util.Map<String,Object> obj = new java.util.LinkedHashMap<>();
                                                            obj.put("type", "WALL");
                                                            obj.put("id", wallObject.getId());
                                                            final ObjectComposition comp = client.getObjectDefinition(wallObject.getId());
                                                            if (comp != null) {
                                                                obj.put("name", comp.getName());
                                                                obj.put("actions", comp.getActions());
                                                            }
                                                            tileObjects.add(obj);
                                                        }
                                                        
                                                        // Decorative objects
                                                        final DecorativeObject decorativeObject = tile.getDecorativeObject();
                                                        if (decorativeObject != null) {
                                                            final java.util.Map<String,Object> obj = new java.util.LinkedHashMap<>();
                                                            obj.put("type", "DECORATIVE");
                                                            obj.put("id", decorativeObject.getId());
                                                            final ObjectComposition comp = client.getObjectDefinition(decorativeObject.getId());
                                                            if (comp != null) {
                                                                obj.put("name", comp.getName());
                                                                obj.put("actions", comp.getActions());
                                                            }
                                                            tileObjects.add(obj);
                                                        }
                                                        
                                                        // Game objects (multiple per tile)
                                                        final GameObject[] gameObjects = tile.getGameObjects();
                                                        if (gameObjects != null) {
                                                            for (final GameObject gameObject : gameObjects) {
                                                                if (gameObject != null) {
                                                                    final java.util.Map<String,Object> obj = new java.util.LinkedHashMap<>();
                                                                    obj.put("type", "GAME_OBJECT");
                                                                    obj.put("id", gameObject.getId());
                                                                    final ObjectComposition comp = client.getObjectDefinition(gameObject.getId());
                                                                    if (comp != null) {
                                                                        obj.put("name", comp.getName());
                                                                        obj.put("actions", comp.getActions());
                                                                    }
                                                                    tileObjects.add(obj);
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        player.put("tileObjects", tileObjects);
                                        
                                        resp.put("ok", true);
                                        resp.put("player", player);
                                        
                                    } catch (Throwable t) {
                                        resp.put("ok", false);
                                        resp.put("err", "get-player-failed");
                                    } finally {
                                        fut.complete(resp);
                                    }
                                });
                                
                                java.util.Map<String,Object> result;
                                try {
                                    result = fut.get(150, java.util.concurrent.TimeUnit.MILLISECONDS);
                                } catch (Exception e) {
                                    result = java.util.Map.of("ok", false, "err", "timeout");
                                }
                                out.println(gson.toJson(result));
                                break;
                            }

                            case "get_equipment": {
                                final java.util.concurrent.CompletableFuture<java.util.Map<String,Object>> fut =
                                        new java.util.concurrent.CompletableFuture<>();
                                
                                clientThread.invokeLater(() -> {
                                    final java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
                                    try {
                                        final ItemContainer equipment = client.getItemContainer(InventoryID.EQUIPMENT);
                                        if (equipment == null) {
                                            resp.put("ok", false);
                                            resp.put("err", "no-equipment-container");
                                            fut.complete(resp);
                                            return;
                                        }
                                        
                                        final Item[] items = equipment.getItems();
                                        if (items == null) {
                                            resp.put("ok", false);
                                            resp.put("err", "no-equipment-items");
                                            fut.complete(resp);
                                            return;
                                        }
                                        
                                        final java.util.Map<String,Object> equipmentData = new java.util.LinkedHashMap<>();
                                        final java.util.List<java.util.Map<String,Object>> slots = new java.util.ArrayList<>();
                                        
                                        // Process each equipment slot
                                        for (final EquipmentInventorySlot slot : EquipmentInventorySlot.values()) {
                                            final int slotIdx = slot.getSlotIdx();
                                            if (slotIdx < items.length) {
                                                final Item item = items[slotIdx];
                                                final java.util.Map<String,Object> slotData = new java.util.LinkedHashMap<>();
                                                
                                                slotData.put("slot", slot.name());
                                                slotData.put("slotIndex", slotIdx);
                                                
                                                if (item != null && item.getId() != -1) {
                                                    final ItemComposition comp = client.getItemDefinition(item.getId());
                                                    slotData.put("id", item.getId());
                                                    slotData.put("quantity", item.getQuantity());
                                                    slotData.put("name", comp != null ? comp.getName() : "Unknown");
                                                    
                                                    if (comp != null) {
                                                        slotData.put("actions", comp.getInventoryActions());
                                                        slotData.put("members", comp.isMembers());
                                                        slotData.put("stackable", comp.isStackable());
                                                    }
                                                    
                                                    // Add item icon as base64
                                                    String iconBase64 = encodeItemImageToBase64(item.getId());
                                                    if (iconBase64 != null) {
                                                        slotData.put("iconBase64", iconBase64);
                                                    }
                                                    
                                                    equipmentData.put(slot.name().toLowerCase(), slotData);
                                                } else {
                                                    slotData.put("id", -1);
                                                    slotData.put("quantity", 0);
                                                    slotData.put("name", "");
                                                    slotData.put("actions", new String[0]);
                                                    slotData.put("members", false);
                                                    slotData.put("stackable", false);
                                                    
                                                    equipmentData.put(slot.name().toLowerCase(), slotData);
                                                }
                                                
                                                slots.add(slotData);
                                            }
                                        }
                                        
                                        resp.put("ok", true);
                                        resp.put("equipment", equipmentData);
                                        resp.put("slots", slots);
                                        resp.put("totalSlots", EquipmentInventorySlot.values().length);
                                        
                                    } catch (Throwable t) {
                                        resp.put("ok", false);
                                        resp.put("err", "get-equipment-failed");
                                    } finally {
                                        fut.complete(resp);
                                    }
                                });
                                
                                java.util.Map<String,Object> result;
                                try {
                                    result = fut.get(150, java.util.concurrent.TimeUnit.MILLISECONDS);
                                } catch (Exception e) {
                                    result = java.util.Map.of("ok", false, "err", "timeout");
                                }
                                out.println(gson.toJson(result));
                                break;
                            }

                            case "get_equipment_inventory": {
                                final java.util.concurrent.CompletableFuture<java.util.Map<String,Object>> fut =
                                        new java.util.concurrent.CompletableFuture<>();
                                
                                clientThread.invokeLater(() -> {
                                    final java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
                                    try {
                                        // Get the EquipmentSide.ITEMS widget (ID 5570560)
                                        Widget equipmentItemsWidget = client.getWidget(5570560);
                                        if (equipmentItemsWidget == null) {
                                            resp.put("ok", false);
                                            resp.put("err", "equipment-inventory-not-found");
                                            fut.complete(resp);
                                            return;
                                        }
                                        
                                        // Check if the equipment interface is open
                                        boolean isHidden = equipmentItemsWidget.isHidden();
                                        if (isHidden) {
                                            resp.put("ok", false);
                                            resp.put("err", "equipment-interface-closed");
                                            fut.complete(resp);
                                            return;
                                        }
                                        
                                        // Use the existing collectChildWidgets method to get all children recursively
                                        java.util.List<java.util.Map<String,Object>> allChildren = new java.util.ArrayList<>();
                                        collectChildWidgets(equipmentItemsWidget, allChildren);
                                        
                                        // Filter for equipment inventory items and process them
                                        java.util.List<java.util.Map<String,Object>> items = new java.util.ArrayList<>();
                                        int slotIndex = 0;
                                        
                                        for (java.util.Map<String,Object> childData : allChildren) {
                                            // Check if this is an equipment inventory item using name field
                                            String name = (String) childData.get("name");
                                            
                                            // Only include items that have names
                                            if (name != null && !name.isEmpty()) {
                                                // Remove color codes like <col=ff9040> and </col>
                                                String itemName = name.replaceAll("<col=[^>]*>", "").replaceAll("</col>", "");
                                                
                                                // Only include items that have actual names (not empty after color code removal)
                                                if (!itemName.isEmpty()) {
                                                    java.util.Map<String,Object> itemData = new java.util.LinkedHashMap<>();
                                                    
                                                    itemData.put("slot", slotIndex++);
                                                    itemData.put("name", itemName);
                                                    itemData.put("rawName", name); // Keep original name with color codes
                                                    itemData.put("widgetId", childData.get("id")); // Include widget ID
                                                    
                                                    // Copy over bounds and visibility data from childData
                                                    itemData.put("visible", childData.get("visible"));
                                                    itemData.put("hasListener", childData.get("hasListener"));
                                                    itemData.put("isIf3", childData.get("isIf3"));
                                                    
                                                    // Get bounds for clicking
                                                    @SuppressWarnings("unchecked")
                                                    java.util.Map<String,Object> bounds = (java.util.Map<String,Object>) childData.get("bounds");
                                                    if (bounds != null) {
                                                        itemData.put("bounds", bounds);
                                                        itemData.put("canvas", java.util.Map.of(
                                                                "x", ((Number)bounds.get("x")).intValue() + ((Number)bounds.get("width")).intValue()/2,
                                                                "y", ((Number)bounds.get("y")).intValue() + ((Number)bounds.get("height")).intValue()/2
                                                        ));
                                                    }
                                                    
                                                    // Check if this slot has an item (non-empty name)
                                                    itemData.put("hasItem", true);
                                                    
                                                    items.add(itemData);
                                                }
                                            }
                                        }
                                        
                                        resp.put("ok", true);
                                        resp.put("items", items);
                                        resp.put("count", items.size());
                                        resp.put("interfaceOpen", !isHidden);
                                        resp.put("totalChildrenFound", allChildren.size());
                                        
                                    } catch (Throwable t) {
                                        resp.put("ok", false);
                                        resp.put("err", "get-equipment-inventory-failed");
                                    } finally {
                                        fut.complete(resp);
                                    }
                                });
                                
                                java.util.Map<String,Object> result;
                                try {
                                    result = fut.get(150, java.util.concurrent.TimeUnit.MILLISECONDS);
                                } catch (Exception e) {
                                    result = java.util.Map.of("ok", false, "err", "timeout");
                                }
                                out.println(gson.toJson(result));
                                break;
                            }

                            case "get_inventory": {
                                final java.util.concurrent.CompletableFuture<java.util.Map<String,Object>> fut =
                                        new java.util.concurrent.CompletableFuture<>();
                                
                                clientThread.invokeLater(() -> {
                                    final java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
                                    try {
                                        final ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
                                        if (inventory == null) {
                                            resp.put("ok", false);
                                            resp.put("err", "no-inventory-container");
                                            fut.complete(resp);
                                            return;
                                        }
                                        
                                        final Item[] items = inventory.getItems();
                                        if (items == null) {
                                            resp.put("ok", false);
                                            resp.put("err", "no-inventory-items");
                                            fut.complete(resp);
                                            return;
                                        }
                                        
                                        final java.util.List<java.util.Map<String,Object>> slots = new java.util.ArrayList<>();
                                        
                                        // Process each inventory slot (0-27)
                                        for (int i = 0; i < 28; i++) {
                                            final java.util.Map<String,Object> slotData = new java.util.LinkedHashMap<>();
                                            
                                            slotData.put("slot", i);
                                            
                                            if (i < items.length) {
                                                final Item item = items[i];
                                                
                                                if (item != null && item.getId() != -1) {
                                                    final ItemComposition comp = client.getItemDefinition(item.getId());
                                                    slotData.put("id", item.getId());
                                                    slotData.put("quantity", item.getQuantity());
                                                    slotData.put("itemName", comp != null ? comp.getName() : "Unknown");
                                                    
                                                    if (comp != null) {
                                                        slotData.put("actions", comp.getInventoryActions());
                                                        slotData.put("members", comp.isMembers());
                                                        slotData.put("stackable", comp.isStackable());
                                                        slotData.put("noted", comp.getNote() == 799);
                                                    }
                                                    
                                                    // Add item icon as base64
                                                    String iconBase64 = encodeItemImageToBase64(item.getId());
                                                    if (iconBase64 != null) {
                                                        slotData.put("iconBase64", iconBase64);
                                                    }
                                                    
                                                    // Get inventory widget bounds for this slot
                                                    try {
                                                        final Widget inventoryWidget = client.getWidget(WidgetInfo.INVENTORY);
                                                        if (inventoryWidget != null && !inventoryWidget.isHidden()) {
                                                            final Widget[] children = inventoryWidget.getChildren();
                                                            if (children != null && i < children.length) {
                                                                final Widget slotWidget = children[i];
                                                                if (slotWidget != null && !slotWidget.isHidden()) {
                                                                    final Rectangle bounds = slotWidget.getBounds();
                                                                    if (bounds != null) {
                                                                        final java.util.Map<String,Object> boundsData = new java.util.LinkedHashMap<>();
                                                                        boundsData.put("x", bounds.x);
                                                                        boundsData.put("y", bounds.y);
                                                                        boundsData.put("width", bounds.width);
                                                                        boundsData.put("height", bounds.height);
                                                                        slotData.put("bounds", boundsData);
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    } catch (Exception e) {
                                                        // Bounds not available, continue without them
                                                    }
                                                } else {
                                                    slotData.put("id", -1);
                                                    slotData.put("quantity", 0);
                                                    slotData.put("itemName", "");
                                                    slotData.put("actions", new String[0]);
                                                    slotData.put("members", false);
                                                    slotData.put("stackable", false);
                                                    slotData.put("noted", false);
                                                    
                                                    // Get empty slot bounds
                                                    try {
                                                        final Widget inventoryWidget = client.getWidget(WidgetInfo.INVENTORY);
                                                        if (inventoryWidget != null && !inventoryWidget.isHidden()) {
                                                            final Widget[] children = inventoryWidget.getChildren();
                                                            if (children != null && i < children.length) {
                                                                final Widget slotWidget = children[i];
                                                                if (slotWidget != null && !slotWidget.isHidden()) {
                                                                    final Rectangle bounds = slotWidget.getBounds();
                                                                    if (bounds != null) {
                                                                        final java.util.Map<String,Object> boundsData = new java.util.LinkedHashMap<>();
                                                                        boundsData.put("x", bounds.x);
                                                                        boundsData.put("y", bounds.y);
                                                                        boundsData.put("width", bounds.width);
                                                                        boundsData.put("height", bounds.height);
                                                                        slotData.put("bounds", boundsData);
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    } catch (Exception e) {
                                                        // Bounds not available, continue without them
                                                    }
                                                }
                                            } else {
                                                slotData.put("id", -1);
                                                slotData.put("quantity", 0);
                                                slotData.put("itemName", "");
                                                slotData.put("actions", new String[0]);
                                                slotData.put("members", false);
                                                slotData.put("stackable", false);
                                                slotData.put("noted", false);
                                                
                                                // Get empty slot bounds
                                                try {
                                                    final Widget inventoryWidget = client.getWidget(WidgetInfo.INVENTORY);
                                                    if (inventoryWidget != null && !inventoryWidget.isHidden()) {
                                                        final Widget[] children = inventoryWidget.getChildren();
                                                        if (children != null && i < children.length) {
                                                            final Widget slotWidget = children[i];
                                                            if (slotWidget != null && !slotWidget.isHidden()) {
                                                                final Rectangle bounds = slotWidget.getBounds();
                                                                if (bounds != null) {
                                                                    final java.util.Map<String,Object> boundsData = new java.util.LinkedHashMap<>();
                                                                    boundsData.put("x", bounds.x);
                                                                    boundsData.put("y", bounds.y);
                                                                    boundsData.put("width", bounds.width);
                                                                    boundsData.put("height", bounds.height);
                                                                    slotData.put("bounds", boundsData);
                                                                }
                                                            }
                                                        }
                                                    }
                                                } catch (Exception e) {
                                                    // Bounds not available, continue without them
                                                }
                                            }
                                            
                                            slots.add(slotData);
                                        }
                                        
                                        resp.put("ok", true);
                                        resp.put("inventory", java.util.Map.of("slots", slots));
                                        resp.put("slots", slots);
                                        resp.put("totalSlots", 28);
                                        
                                    } catch (Throwable t) {
                                        resp.put("ok", false);
                                        resp.put("err", "get-inventory-failed");
                                    } finally {
                                        fut.complete(resp);
                                    }
                                });
                                
                                java.util.Map<String,Object> result;
                                try {
                                    result = fut.get(150, java.util.concurrent.TimeUnit.MILLISECONDS);
                                } catch (Exception e) {
                                    result = java.util.Map.of("ok", false, "err", "timeout");
                                }
                                out.println(gson.toJson(result));
                                break;
                            }

                            case "get_bank_inventory": {
                                final java.util.concurrent.CompletableFuture<java.util.Map<String,Object>> fut =
                                        new java.util.concurrent.CompletableFuture<>();
                                
                                clientThread.invokeLater(() -> {
                                    final java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
                                    try {
                                        // Get the Bankside.ITEMS widget (ID 983043)
                                        Widget bankItemsWidget = client.getWidget(983043);
                                        if (bankItemsWidget == null) {
                                            resp.put("ok", false);
                                            resp.put("err", "bank-inventory-not-found");
                                            fut.complete(resp);
                                            return;
                                        }
                                        
                                        // Check if the bank interface is open
                                        boolean isHidden = bankItemsWidget.isHidden();
                                        if (isHidden) {
                                            resp.put("ok", false);
                                            resp.put("err", "bank-interface-closed");
                                            fut.complete(resp);
                                            return;
                                        }
                                        
                                        // Use the existing collectChildWidgets method to get all children recursively
                                        java.util.List<java.util.Map<String,Object>> allChildren = new java.util.ArrayList<>();
                                        collectChildWidgets(bankItemsWidget, allChildren);
                                        
                                        // Filter for bank inventory items and process them
                                        java.util.List<java.util.Map<String,Object>> items = new java.util.ArrayList<>();
                                        int slotIndex = 0;
                                        
                                        for (java.util.Map<String,Object> childData : allChildren) {
                                            // Check if this is a bank inventory item using name field
                                            String name = (String) childData.get("name");
                                            
                                            // Only include items that have names
                                            if (name != null && !name.isEmpty()) {
                                                // Remove color codes like <col=ff9040> and </col>
                                                String itemName = name.replaceAll("<col=[^>]*>", "").replaceAll("</col>", "");
                                                
                                                // Only include items that have actual names (not empty after color code removal)
                                                if (!itemName.isEmpty()) {
                                                    java.util.Map<String,Object> itemData = new java.util.LinkedHashMap<>();
                                                    
                                                    itemData.put("slot", slotIndex++);
                                                    itemData.put("name", itemName);
                                                    itemData.put("rawName", name); // Keep original name with color codes
                                                    itemData.put("widgetId", childData.get("id")); // Include widget ID
                                                    
                                                    // Copy over bounds and visibility data from childData
                                                    itemData.put("visible", childData.get("visible"));
                                                    itemData.put("hasListener", childData.get("hasListener"));
                                                    itemData.put("isIf3", childData.get("isIf3"));
                                                    
                                                    // Get bounds for clicking
                                                    @SuppressWarnings("unchecked")
                                                    java.util.Map<String,Object> bounds = (java.util.Map<String,Object>) childData.get("bounds");
                                                    if (bounds != null) {
                                                        itemData.put("bounds", bounds);
                                                        itemData.put("canvas", java.util.Map.of(
                                                                "x", ((Number)bounds.get("x")).intValue() + ((Number)bounds.get("width")).intValue()/2,
                                                                "y", ((Number)bounds.get("y")).intValue() + ((Number)bounds.get("height")).intValue()/2
                                                        ));
                                                    }
                                                    
                                                    // Check if this slot has an item (non-empty name)
                                                    itemData.put("hasItem", true);
                                                    
                                                    items.add(itemData);
                                                }
                                            }
                                        }
                                        
                                        resp.put("ok", true);
                                        resp.put("items", items);
                                        resp.put("count", items.size());
                                        resp.put("interfaceOpen", !isHidden);
                                        resp.put("totalChildrenFound", allChildren.size());
                                        
                                    } catch (Throwable t) {
                                        resp.put("ok", false);
                                        resp.put("err", "get-bank-inventory-failed");
                                    } finally {
                                        fut.complete(resp);
                                    }
                                });
                                
                                java.util.Map<String,Object> result;
                                try {
                                    result = fut.get(150, java.util.concurrent.TimeUnit.MILLISECONDS);
                                } catch (Exception e) {
                                    result = java.util.Map.of("ok", false, "err", "timeout");
                                }
                                out.println(gson.toJson(result));
                                break;
                            }

                            case "get_bank": {
                                final java.util.concurrent.CompletableFuture<java.util.Map<String,Object>> fut =
                                        new java.util.concurrent.CompletableFuture<>();
                                
                                clientThread.invokeLater(() -> {
                                    final java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
                                    try {
                                        // Get the actual bank contents using RuneLite's bank container
                                        final ItemContainer bankContainer = client.getItemContainer(InventoryID.BANK);
                                        if (bankContainer == null) {
                                            resp.put("ok", false);
                                            resp.put("err", "no-bank-container");
                                            fut.complete(resp);
                                            return;
                                        }
                                        
                                        final Item[] bankItems = bankContainer.getItems();
                                        if (bankItems == null) {
                                            resp.put("ok", false);
                                            resp.put("err", "no-bank-items");
                                            fut.complete(resp);
                                            return;
                                        }
                                        
                                        final java.util.List<java.util.Map<String,Object>> items = new java.util.ArrayList<>();
                                        
                                        // Process each bank slot
                                        for (int i = 0; i < bankItems.length; i++) {
                                            final Item item = bankItems[i];
                                            final java.util.Map<String,Object> itemData = new java.util.LinkedHashMap<>();
                                            
                                            itemData.put("slot", i);
                                            
                                            // Get widget bounds for this bank slot
                                            // Updated: Bank items are now in TABS_LINE0 (12.12) instead of ITEMS (12.13)
                                            // Widget IDs are packed as (group << 16) | child
                                            // For slot i in widget 12.12, the widget ID is (12 << 16) | (12 + i)
                                            final int groupId = 12;
                                            final int baseChildId = 12; // TABS_LINE0
                                            final int slotWidgetId = (groupId << 16) | (baseChildId + i);
                                            final Widget slotWidget = client.getWidget(slotWidgetId);
                                            final java.util.Map<String,Object> bounds = new java.util.LinkedHashMap<>();
                                            
                                            if (slotWidget != null && !slotWidget.isHidden()) {
                                                bounds.put("x", slotWidget.getCanvasLocation().getX());
                                                bounds.put("y", slotWidget.getCanvasLocation().getY());
                                                bounds.put("width", slotWidget.getWidth());
                                                bounds.put("height", slotWidget.getHeight());
                                                bounds.put("visible", true);
                                            } else {
                                                // Fallback: try getting from container's children array
                                                final int containerWidgetId = (groupId << 16) | baseChildId; // 12.12 = 786444
                                                final Widget bankItemsContainer = client.getWidget(containerWidgetId);
                                                if (bankItemsContainer != null) {
                                                    Widget[] children = bankItemsContainer.getDynamicChildren();
                                                    if (children == null || children.length == 0) {
                                                        children = bankItemsContainer.getChildren();
                                                    }
                                                    
                                                    if (children != null && i < children.length && children[i] != null) {
                                                        final Widget childWidget = children[i];
                                                        bounds.put("x", childWidget.getCanvasLocation().getX());
                                                        bounds.put("y", childWidget.getCanvasLocation().getY());
                                                        bounds.put("width", childWidget.getWidth());
                                                        bounds.put("height", childWidget.getHeight());
                                                        bounds.put("visible", !childWidget.isHidden());
                                                    } else {
                                                        bounds.put("x", 0);
                                                        bounds.put("y", 0);
                                                        bounds.put("width", 0);
                                                        bounds.put("height", 0);
                                                        bounds.put("visible", false);
                                                    }
                                                } else {
                                                    bounds.put("x", 0);
                                                    bounds.put("y", 0);
                                                    bounds.put("width", 0);
                                                    bounds.put("height", 0);
                                                    bounds.put("visible", false);
                                                }
                                            }
                                            
                                            itemData.put("bounds", bounds);
                                            
                                            if (item != null && item.getId() != -1) {
                                                final ItemComposition comp = client.getItemDefinition(item.getId());
                                                itemData.put("id", item.getId());
                                                itemData.put("quantity", item.getQuantity());
                                                itemData.put("name", comp != null ? comp.getName() : "Unknown");
                                                
                                                if (comp != null) {
                                                    itemData.put("actions", comp.getInventoryActions());
                                                    itemData.put("members", comp.isMembers());
                                                    itemData.put("stackable", comp.isStackable());
                                                }
                                            } else {
                                                itemData.put("id", -1);
                                                itemData.put("quantity", 0);
                                                itemData.put("name", "");
                                                itemData.put("actions", new String[0]);
                                                itemData.put("members", false);
                                                itemData.put("stackable", false);
                                            }
                                            
                                            items.add(itemData);
                                        }
                                        
                                        // Get bank interface bounds
                                        final Widget bankMain = client.getWidget(786433);
                                        final java.util.Map<String,Object> bounds = new java.util.LinkedHashMap<>();
                                        
                                        if (bankMain != null) {
                                            bounds.put("x", bankMain.getCanvasLocation().getX());
                                            bounds.put("y", bankMain.getCanvasLocation().getY());
                                            bounds.put("width", bankMain.getWidth());
                                            bounds.put("height", bankMain.getHeight());
                                            bounds.put("visible", !bankMain.isHidden());
                                        } else {
                                            bounds.put("x", 0);
                                            bounds.put("y", 0);
                                            bounds.put("width", 0);
                                            bounds.put("height", 0);
                                            bounds.put("visible", false);
                                        }
                                        
                                        resp.put("ok", true);
                                        resp.put("items", items);
                                        resp.put("count", items.size());
                                        resp.put("totalSlots", bankItems.length);
                                        resp.put("bounds", bounds);
                                        
                                    } catch (Throwable t) {
                                        resp.put("ok", false);
                                        resp.put("err", "get-bank-failed");
                                    } finally {
                                        fut.complete(resp);
                                    }
                                });
                                
                                java.util.Map<String,Object> result;
                                try {
                                    result = fut.get(150, java.util.concurrent.TimeUnit.MILLISECONDS);
                                } catch (Exception e) {
                                    result = java.util.Map.of("ok", false, "err", "timeout");
                                }
                                out.println(gson.toJson(result));
                                break;
                            }

                            case "bank-xvalue": {
                                final java.util.concurrent.CompletableFuture<java.util.Map<String,Object>> fut =
                                        new java.util.concurrent.CompletableFuture<>();
                                
                                clientThread.invokeLater(() -> {
                                    final java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
                                    try {
                                        // Check if client is in the right state
                                        if (client.getGameState() != GameState.LOGGED_IN) {
                                            resp.put("ok", false);
                                            resp.put("err", "not-logged-in");
                                            fut.complete(resp);
                                            return;
                                        }
                                        
                                        final int mode = client.getVarbitValue(Varbits.BANK_QUANTITY_TYPE);
                                        final int xVal = client.getVarbitValue(Varbits.BANK_REQUESTEDQUANTITY);
                                        resp.put("ok", true);
                                        resp.put("mode", mode);
                                        resp.put("x", xVal);
                                    } catch (Throwable t) {
                                        resp.put("ok", false);
                                        resp.put("err", "bank-xvalue-failed");
                                        resp.put("exception", t.getMessage());
                                    } finally {
                                        fut.complete(resp);
                                    }
                                });
                                
                                java.util.Map<String,Object> result;
                                try {
                                    result = fut.get(150, java.util.concurrent.TimeUnit.MILLISECONDS);
                                } catch (Exception e) {
                                    result = java.util.Map.of("ok", false, "err", "timeout");
                                }
                                out.println(gson.toJson(result));
                                break;
                            }

                            case "get_quests": {
                                final java.util.concurrent.CompletableFuture<java.util.Map<String,Object>> fut =
                                        new java.util.concurrent.CompletableFuture<>();
                                
                                clientThread.invokeLater(() -> {
                                    final java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
                                    try {
                                        resp.put("ok", true);
                                        
                                        // Get quest states for all quests
                                        java.util.Map<String, Object> quests = new java.util.LinkedHashMap<>();
                                        for (net.runelite.api.Quest q : net.runelite.api.Quest.values()) {
                                            net.runelite.api.QuestState st = q.getState(client);
                                            quests.put(q.getName(), (st != null ? st.name() : null)); // e.g., NOT_STARTED / IN_PROGRESS / FINISHED
                                        }
                                        resp.put("quests", quests);
                                        
                                    } catch (Exception e) {
                                        resp.put("ok", false);
                                        resp.put("error", e.getMessage());
                                    } finally {
                                        fut.complete(resp);
                                    }
                                });
                                
                                java.util.Map<String,Object> result;
                                try {
                                    result = fut.get(150, java.util.concurrent.TimeUnit.MILLISECONDS);
                                } catch (Exception e) {
                                    result = java.util.Map.of("ok", false, "err", "timeout");
                                }
                                out.println(gson.toJson(result));
                                break;
                            }

                            case "get_varc_int": {
                                Integer varId = cmd.var_id;
                                if (varId == null) {
                                    out.println("{\"ok\":false,\"err\":\"need var_id\"}");
                                    break;
                                }
                                
                                final java.util.concurrent.CompletableFuture<java.util.Map<String,Object>> fut =
                                        new java.util.concurrent.CompletableFuture<>();
                                
                                clientThread.invokeLater(() -> {
                                    final java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
                                    try {
                                        int value = client.getVarcIntValue(varId);
                                        resp.put("ok", true);
                                        resp.put("value", value);
                                        resp.put("var_id", varId);
                                    } catch (Throwable t) {
                                        resp.put("ok", false);
                                        resp.put("err", "get-varc-int-failed");
                                    } finally {
                                        fut.complete(resp);
                                    }
                                });
                                
                                java.util.Map<String,Object> result;
                                try {
                                    result = fut.get(150, java.util.concurrent.TimeUnit.MILLISECONDS);
                                } catch (Exception e) {
                                    result = java.util.Map.of("ok", false, "err", "timeout");
                                }
                                out.println(gson.toJson(result));
                                break;
                            }

                            case "get_varp": {
                                Integer varpId = cmd.id;
                                if (varpId == null) {
                                    out.println("{\"ok\":false,\"err\":\"need id\"}");
                                    break;
                                }
                                
                                final java.util.concurrent.CompletableFuture<java.util.Map<String,Object>> fut =
                                        new java.util.concurrent.CompletableFuture<>();
                                
                                clientThread.invokeLater(() -> {
                                    final java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
                                    try {
                                        int value = client.getVarpValue(varpId);
                                        resp.put("ok", true);
                                        resp.put("value", value);
                                        resp.put("varp_id", varpId);
                                    } catch (Throwable t) {
                                        resp.put("ok", false);
                                        resp.put("err", "get-varp-failed");
                                    } finally {
                                        fut.complete(resp);
                                    }
                                });
                                
                                java.util.Map<String,Object> result;
                                try {
                                    result = fut.get(150, java.util.concurrent.TimeUnit.MILLISECONDS);
                                } catch (Exception e) {
                                    result = java.util.Map.of("ok", false, "err", "timeout");
                                }
                                out.println(gson.toJson(result));
                                break;
                            }

                            case "get_spellbook": {
                                final java.util.concurrent.CompletableFuture<java.util.Map<String,Object>> fut =
                                        new java.util.concurrent.CompletableFuture<>();
                                
                                clientThread.invokeLater(() -> {
                                    final java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
                                    try {
                                        // Get the MagicSpellbook.SPELLLAYER widget (ID 14286851)
                                        Widget spellLayer = client.getWidget(14286851);
                                        if (spellLayer != null) {
                                            java.util.List<java.util.Map<String,Object>> spells = new java.util.ArrayList<>();
                                            
                                            // Get all child widgets
                                            Widget[] children = spellLayer.getStaticChildren();
                                            if (children != null) {
                                                for (Widget child : children) {
                                                    if (child != null) {
                                                        java.util.Map<String,Object> spellData = new java.util.LinkedHashMap<>();
                                                        spellData.put("id", child.getId());
                                                        spellData.put("name", child.getName());
                                                        
                                                        // Get bounds if available
                                                        java.awt.Rectangle bounds = child.getBounds();
                                                        if (bounds != null) {
                                                            spellData.put("bounds", java.util.Map.of(
                                                                    "x", bounds.x,
                                                                    "y", bounds.y,
                                                                    "width", bounds.width,
                                                                    "height", bounds.height
                                                            ));
                                                            spellData.put("canvas", java.util.Map.of(
                                                                    "x", bounds.x + bounds.width/2,
                                                                    "y", bounds.y + bounds.height/2
                                                            ));
                                                        }
                                                        
                                                        // Check if widget is visible and clickable
                                                        spellData.put("visible", bounds != null && bounds.x >= 0 && bounds.y >= 0);
                                                        spellData.put("hasListener", child.hasListener());
                                                        
                                                        spells.add(spellData);
                                                    }
                                                }
                                            }
                                            
                                            resp.put("ok", true);
                                            resp.put("spells", spells);
                                            resp.put("count", spells.size());
                                        } else {
                                            resp.put("ok", false);
                                            resp.put("err", "spellbook-not-found");
                                        }
                                        
                                    } catch (Throwable t) {
                                        resp.put("ok", false);
                                        resp.put("err", "get-spellbook-failed");
                                    } finally {
                                        fut.complete(resp);
                                    }
                                });
                                
                                java.util.Map<String,Object> result;
                                try {
                                    result = fut.get(150, java.util.concurrent.TimeUnit.MILLISECONDS);
                                } catch (Exception e) {
                                    result = java.util.Map.of("ok", false, "err", "timeout");
                                }
                                out.println(gson.toJson(result));
                                break;
                            }

                            case "get_camera": {
                                final java.util.concurrent.CompletableFuture<java.util.Map<String,Object>> fut =
                                        new java.util.concurrent.CompletableFuture<>();
                                
                                clientThread.invokeLater(() -> {
                                    final java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
                                    try {
                                        // Get camera scale (zoom level) - this is the key method we need
                                        int cameraScale = client.getScale();
                                        
                                        // Get camera pitch (vertical angle)
                                        int cameraPitch = client.getCameraPitch();
                                        
                                        // Get camera yaw (horizontal angle)
                                        int cameraYaw = client.getCameraYaw();
                                        
                                        // Get camera position coordinates
                                        int cameraX = client.getCameraX();
                                        int cameraY = client.getCameraY();
                                        int cameraZ = client.getCameraZ();
                                        
                                        resp.put("ok", true);
                                        resp.put("scale", cameraScale);
                                        resp.put("pitch", cameraPitch);
                                        resp.put("yaw", cameraYaw);
                                        
                                        // Camera position as coordinates
                                        resp.put("position", java.util.Map.of(
                                                "x", cameraX,
                                                "y", cameraY,
                                                "z", cameraZ
                                        ));
                                        
                                        // Additional camera info
                                        resp.put("plane", client.getPlane());
                                        resp.put("baseX", client.getBaseX());
                                        resp.put("baseY", client.getBaseY());
                                        
                                    } catch (Throwable t) {
                                        resp.put("ok", false);
                                        resp.put("err", "get-camera-failed");
                                    } finally {
                                        fut.complete(resp);
                                    }
                                });
                                
                                java.util.Map<String,Object> result;
                                try {
                                    result = fut.get(150, java.util.concurrent.TimeUnit.MILLISECONDS);
                                } catch (Exception e) {
                                    result = java.util.Map.of("ok", false, "err", "timeout");
                                }
                                out.println(gson.toJson(result));
                                break;
                            }

                            case "ground_items": {
                                final String needle = (cmd.name == null ? "" : cmd.name.trim().toLowerCase());
                                final int radius = (cmd.radius == null) ? 10 : Math.max(1, cmd.radius);

                                final java.util.concurrent.CompletableFuture<java.util.Map<String,Object>> fut =
                                        new java.util.concurrent.CompletableFuture<>();

                                clientThread.invokeLater(() -> {
                                    final java.util.Map<String,Object> resp = new java.util.LinkedHashMap<>();
                                    try {
                                        final Player me = client.getLocalPlayer();
                                        final Scene scene = client.getScene();
                                        if (me == null || scene == null) { 
                                            resp.put("ok", false); resp.put("err", "no-player-or-scene"); 
                                            fut.complete(resp); return; 
                                        }

                                        final int plane = client.getPlane();
                                        final int baseX = client.getBaseX(), baseY = client.getBaseY();
                                        final int myWx = me.getWorldLocation().getX();
                                        final int myWy = me.getWorldLocation().getY();
                                        final int minWx = myWx - radius, maxWx = myWx + radius;
                                        final int minWy = myWy - radius, maxWy = myWy + radius;

                                        // Get all ground items from the scene
                                        final Tile[][][] tiles = scene.getTiles();
                                        if (tiles == null || plane < 0 || plane >= tiles.length || tiles[plane] == null) {
                                            resp.put("ok", true); resp.put("items", new java.util.ArrayList<>());
                                            fut.complete(resp); return; 
                                        }

                                        // Collect all matching ground items and find the closest one
                                        java.util.List<java.util.Map<String,Object>> items = new java.util.ArrayList<>();
                                        
                                        // Search through all tiles in the scene for ground items
                                        for (int lx = 0; lx < 104; lx++) {
                                            final Tile[] col = tiles[plane][lx];
                                            if (col == null) continue;
                                            for (int ly = 0; ly < 104; ly++) {
                                                final Tile tile = col[ly];
                                                if (tile == null) continue;

                                                final int wx = baseX + lx;
                                                final int wy = baseY + ly;
                                                
                                                // Check if within radius
                                                if (wx < minWx || wx > maxWx || wy < minWy || wy > maxWy) continue;

                                                // Check for ground items using getGroundItems()
                                                java.util.List<?> tileGroundItems = tile.getGroundItems();
                                                if (tileGroundItems != null && !tileGroundItems.isEmpty()) {
                                                    for (Object groundItem : tileGroundItems) {
                                                        try {
                                                            // Get item ID using reflection
                                                            java.lang.reflect.Method getIdMethod = groundItem.getClass().getMethod("getId");
                                                            int itemId = (Integer) getIdMethod.invoke(groundItem);
                                                            
                                                            // Get item name using client.getItemDefinition()
                                                            ItemComposition comp = client.getItemDefinition(itemId);
                                                            String itemName = comp != null ? comp.getName() : "Unknown";
                                                            String itemNameLower = itemName.toLowerCase();
                                                            
                                                            // Only include items that match our search term
                                                            if (!needle.isEmpty() && !itemNameLower.contains(needle)) continue;
                                                            
                                                            // Get screen coordinates
                                                            net.runelite.api.coords.LocalPoint localPoint = tile.getLocalLocation();
                                                            net.runelite.api.Point screenPoint = null;
                                                            if (localPoint != null) {
                                                                screenPoint = net.runelite.api.Perspective.localToCanvas(client, localPoint, plane);
                                                            }
                                                            
                                                            // Build ground item data
                                                            final java.util.Map<String,Object> itemData = new java.util.LinkedHashMap<>();
                                                            itemData.put("type", "GROUND_ITEM");
                                                            itemData.put("id", itemId);
                                                            itemData.put("name", itemName);
                                                            itemData.put("quantity", 1); // Ground items typically have quantity 1
                                                            
                                                            // Get ground item actions (not inventory actions)
                                                            String[] groundActions = null;
                                                            if (comp != null) {
                                                                // Try to get ground item actions using reflection
                                                                try {
                                                                    java.lang.reflect.Method getGroundActionsMethod = comp.getClass().getMethod("getGroundActions");
                                                                    groundActions = (String[]) getGroundActionsMethod.invoke(comp);
                                                                } catch (Exception e) {
                                                                    // Fallback to inventory actions if ground actions not available
                                                                    groundActions = comp.getInventoryActions();
                                                                }
                                                            }
                                                            itemData.put("actions", groundActions);
                                                            itemData.put("world", java.util.Map.of("x", wx, "y", wy, "p", plane));
                                                            itemData.put("distance", Math.abs(wx - myWx) + Math.abs(wy - myWy));
                                                            
                                                            // Add screen coordinates
                                                            if (screenPoint != null) {
                                                                itemData.put("canvas", java.util.Map.of("x", screenPoint.getX(), "y", screenPoint.getY()));
                                                                itemData.put("bounds", java.util.Map.of(
                                                                        "x", screenPoint.getX() - 8, "y", screenPoint.getY() - 8, "width", 16, "height", 16));
                                                                itemData.put("clickbox", java.util.Map.of(
                                                                        "x", screenPoint.getX() - 8, "y", screenPoint.getY() - 8, "width", 16, "height", 16));
                                                            }
                                                            
                                                            items.add(itemData);
                                                        } catch (Exception e) {
                                                            // Skip items that can't be processed
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        resp.put("ok", true);
                                        resp.put("items", items);
                                        resp.put("count", items.size());

                                    } catch (Throwable t) {
                                        resp.put("ok", false); resp.put("err", "ground-items-failed");
                                    } finally {
                                        fut.complete(resp);
                                    }
                                });

                                java.util.Map<String,Object> result;
                                try {
                                    result = fut.get(150, java.util.concurrent.TimeUnit.MILLISECONDS);
                                } catch (Exception e) {
                                    result = java.util.Map.of("ok", false, "err", "timeout");
                                }
                                out.println(gson.toJson(result));
                                break;
                            }

                            case "get_tutorial": {
                                final java.util.concurrent.CompletableFuture<java.util.Map<String,Object>> fut =
                                        new java.util.concurrent.CompletableFuture<>();
                                
                                clientThread.invokeLater(() -> {
                                    try {
                                        java.util.Map<String, Object> tutorial = new java.util.HashMap<>();
                                        
                                        // Main tutorial interface widget: TutorialDisplayname.MAIN (558.2)
                                        Widget mainWidget = client.getWidget(558, 2);
                                        boolean tutorialOpen = (mainWidget != null && !mainWidget.isHidden());
                                        tutorial.put("open", tutorialOpen);
                                        
                                        if (tutorialOpen) {
                                            tutorial.put("interface", "character_creation");
                                            
                                            // Main widget bounds
                                            java.util.Map<String, Object> mainInfo = new java.util.HashMap<>();
                                            mainInfo.put("id", 36569090); // TutorialDisplayname.MAIN
                                            mainInfo.put("bounds", widgetBoundsJson(mainWidget));
                                            mainInfo.put("visible", !mainWidget.isHidden());
                                            tutorial.put("main", mainInfo);
                                            
                                            // Name input widget: TutorialDisplayname.NAME (558.7)
                                            Widget nameWidget = client.getWidget(558, 7);
                                            if (nameWidget != null) {
                                                java.util.Map<String, Object> nameInfo = new java.util.HashMap<>();
                                                nameInfo.put("id", 36569095); // TutorialDisplayname.NAME
                                                nameInfo.put("bounds", widgetBoundsJson(nameWidget));
                                                nameInfo.put("visible", !nameWidget.isHidden());
                                                nameInfo.put("text", safeText(nameWidget));
                                                tutorial.put("nameInput", nameInfo);
                                            }
                                            
                                            // Name text widget: TutorialDisplayname.NAME_TEXT (558.12)
                                            Widget nameTextWidget = client.getWidget(558, 12);
                                            if (nameTextWidget != null) {
                                                java.util.Map<String, Object> nameTextInfo = new java.util.HashMap<>();
                                                nameTextInfo.put("id", 36569100); // TutorialDisplayname.NAME_TEXT
                                                nameTextInfo.put("bounds", widgetBoundsJson(nameTextWidget));
                                                nameTextInfo.put("visible", !nameTextWidget.isHidden());
                                                nameTextInfo.put("text", safeText(nameTextWidget));
                                                tutorial.put("nameText", nameTextInfo);
                                            }
                                            
                                            // Status widget: TutorialDisplayname.STATUS (558.13)
                                            Widget statusWidget = client.getWidget(558, 13);
                                            if (statusWidget != null) {
                                                java.util.Map<String, Object> statusInfo = new java.util.HashMap<>();
                                                statusInfo.put("id", 36569101); // TutorialDisplayname.STATUS
                                                statusInfo.put("bounds", widgetBoundsJson(statusWidget));
                                                statusInfo.put("visible", !statusWidget.isHidden());
                                                statusInfo.put("text", safeText(statusWidget));
                                                tutorial.put("status", statusInfo);
                                            }
                                            
                                            // Set name widget: TutorialDisplayname.SET_NAME (558.19)
                                            Widget setNameWidget = client.getWidget(558, 19);
                                            if (setNameWidget != null) {
                                                java.util.Map<String, Object> setNameInfo = new java.util.HashMap<>();
                                                setNameInfo.put("id", 36569107); // TutorialDisplayname.SET_NAME
                                                setNameInfo.put("bounds", widgetBoundsJson(setNameWidget));
                                                setNameInfo.put("visible", !setNameWidget.isHidden());
                                                setNameInfo.put("text", safeText(setNameWidget));
                                                tutorial.put("setName", setNameInfo);
                                            }
                                        } else {
                                            tutorial.put("interface", null);
                                        }
                                        
                                        java.util.Map<String, Object> result = new java.util.HashMap<>();
                                        result.put("ok", true);
                                        result.put("tutorial", tutorial);
                                        fut.complete(result);
                                        
                                    } catch (Exception e) {
                                        java.util.Map<String, Object> result = new java.util.HashMap<>();
                                        result.put("ok", false);
                                        result.put("err", "tutorial-failed: " + e.getMessage());
                                        fut.complete(result);
                                    }
                                });
                                
                                try {
                                    java.util.Map<String, Object> result = fut.get(2, java.util.concurrent.TimeUnit.SECONDS);
                                    out.println(gson.toJson(result));
                                } catch (Exception e) {
                                    out.println("{\"ok\":false,\"err\":\"tutorial-timeout\"}");
                                }
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

        // Snapshot of where door-like WallObjects exist on the current plane.
// Returns doorHere[104][104], true if a tile has a "door"/"gate" wall object.
        private boolean[][] buildDoorHereMaskOnClientThread(
                Client client,
                ClientThread clientThread,
                int plane,
                int timeoutMs
        ) {
            final java.util.concurrent.CompletableFuture<boolean[][]> fut = new java.util.concurrent.CompletableFuture<>();

            clientThread.invokeLater(() -> {
                final boolean[][] doorHere = new boolean[104][104];
                try {
                    final Scene scene = client.getScene();
                    if (scene == null) { fut.complete(doorHere); return; }

                    final Tile[][][] tilesArr = scene.getTiles();
                    if (tilesArr == null || plane < 0 || plane >= tilesArr.length || tilesArr[plane] == null) {
                        fut.complete(doorHere); return;
                    }

                    final int baseX = client.getBaseX();
                    final int baseY = client.getBaseY();

                    for (int lx = 0; lx < 104; lx++) {
                        final Tile[] col = tilesArr[plane][lx];
                        if (col == null) continue;
                        for (int ly = 0; ly < 104; ly++) {
                            final Tile tile = col[ly];
                            if (tile == null) continue;

                            final WallObject wobj = tile.getWallObject();
                            if (wobj == null) continue;

                            // "Door" / "Gate" heuristic
                            try {
                                final ObjectComposition comp = client.getObjectDefinition(wobj.getId());
                                final String nm = (comp != null && comp.getName() != null) ? comp.getName().toLowerCase() : "";
                                if (!nm.isEmpty() && (nm.contains("door") || nm.contains("gate"))) {
                                    doorHere[lx][ly] = true;
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                } catch (Throwable t) {
                    // fall through with all-false mask
                } finally {
                    fut.complete(doorHere);
                }
            });

            try {
                return fut.get(Math.max(1, timeoutMs), java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                return new boolean[104][104]; // all false
            }
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

            // Snapshot where doors are in-scene on this plane (client thread safe)
            final boolean[][] doorHere = buildDoorHereMaskOnClientThread(client, clientThread, dbg.plane, 100);

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

                    if (!canStep(flags, doorHere, cx, cy, dx, dy)) {
                        // optional: diagnostics counters
                        // if (dx == 0 || dy == 0) skippedBlocked++; else skippedCorner++;
                        continue;
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
            final WorldPoint startWp = new WorldPoint(dbg.startLx + dbg.baseX, dbg.startLy + dbg.baseY, dbg.plane);
            final WorldPoint endWp   = new WorldPoint(endLx        + dbg.baseX, endLy        + dbg.baseY, dbg.plane);

            final ArrayList<WorldPoint> path = new ArrayList<>();

            if (endLx == dbg.startLx && endLy == dbg.startLy) {
                // Standing on the goal: return [start] so callers see the origin tile.
                path.add(startWp);
            } else {
                // Reconstruct inclusively, from END back to START, then reverse.
                int px = endLx, py = endLy;
                while (true) {
                    path.add(new WorldPoint(px + dbg.baseX, py + dbg.baseY, dbg.plane)); // include END and all intermediates
                    if (px == dbg.startLx && py == dbg.startLy) break;

                    // move to predecessor using your existing came-from data:
                    // (replace 'prevX/prevY' with whatever you already have)
                    final int packed = prev[px][py];
                    final int npx = (packed >>> 16) & 0xFFFF;
                    final int npy = (packed       ) & 0xFFFF;
                    px = npx; py = npy;
                }
                java.util.Collections.reverse(path);

                // Ensure START is index 0 (defensive; should already be after reverse).
                if (path.isEmpty() || !path.get(0).equals(startWp)) {
                    path.add(0, startWp);
                }
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



        private void typeStringAWT(Client client,
                                   String text,
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
                        
                        // Add orientation A and B
                        door.put("orientationA", wobj.getOrientationA());
                        door.put("orientationB", wobj.getOrientationB());

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

        private Map<String,Object> getObjectsOnClientThread(
                String query,
                java.util.Set<String> types,
                int radius,
                int timeoutMs
        ) {
            final java.util.concurrent.CompletableFuture<Map<String,Object>> fut = new java.util.concurrent.CompletableFuture<>();

            clientThread.invokeLater(() -> {
                final Map<String,Object> resp = new LinkedHashMap<>();
                final java.util.List<Map<String,Object>> out = new java.util.ArrayList<>();
                try {
                    final Player me = client.getLocalPlayer();
                    final Scene scene = client.getScene();
                    if (me == null || scene == null) { resp.put("ok", false); resp.put("err", "no-player-or-scene"); fut.complete(resp); return; }

                    final int plane = client.getPlane();
                    final int baseX = client.getBaseX(), baseY = client.getBaseY();
                    final int cx = me.getWorldLocation().getX(), cy = me.getWorldLocation().getY();

                    final Tile[][][] tiles = scene.getTiles();
                    if (tiles == null || plane < 0 || plane >= tiles.length || tiles[plane] == null) {
                        resp.put("ok", false); resp.put("err", "no-tiles"); fut.complete(resp); return;
                    }

                    final String q = query.toLowerCase();

                    // scan square neighborhood (clamped to scene)
                    final int minWx = Math.max(baseX, cx - radius);
                    final int maxWx = Math.min(baseX + 103, cx + radius);
                    final int minWy = Math.max(baseY, cy - radius);
                    final int maxWy = Math.min(baseY + 103, cy + radius);

                    for (int wx = minWx; wx <= maxWx; wx++) {
                        for (int wy = minWy; wy <= maxWy; wy++) {
                            final int lx = wx - baseX, ly = wy - baseY;
                            final Tile tile = tiles[plane][lx][ly];
                            if (tile == null) continue;

                            // WALL objects
                            if (types.contains("WALL")) {
                                final WallObject w = tile.getWallObject();
                                if (w != null) collectObject(out, w.getId(), "WALL", wx, wy, plane, w.getConvexHull(), q);
                            }

                            // GAME objects (1–5 slots)
                            if (types.contains("GAME")) {
                                final GameObject[] gos = tile.getGameObjects();
                                if (gos != null) for (GameObject go : gos) {
                                    if (go != null) collectObject(out, go.getId(), "GAME", wx, wy, plane, go.getConvexHull(), q);
                                }
                            }

                            // DECOR (floor dec)
                            if (types.contains("DECOR")) {
                                final DecorativeObject d = tile.getDecorativeObject();
                                if (d != null) collectObject(out, d.getId(), "DECOR", wx, wy, plane, d.getConvexHull(), q);
                            }
                        }
                    }

                    // pack response
                    resp.put("ok", true);
                    // include player world (helps client side scoring)
                    resp.put("player", java.util.Map.of("x", cx, "y", cy, "p", plane));
                    resp.put("objects", out);
                } catch (Throwable t) {
                    resp.put("ok", false); resp.put("err", "scan-failed");
                } finally {
                    fut.complete(resp);
                }
            });

            try { return fut.get(Math.max(1, timeoutMs), java.util.concurrent.TimeUnit.MILLISECONDS); }
            catch (Exception e) { return java.util.Map.of("ok", false, "err", "timeout"); }
        }

        private void collectObject(
                java.util.List<Map<String,Object>> out,
                int id,
                String type,
                int wx, int wy, int plane,
                java.awt.Shape hull,
                String qLower
        ) {
            // Name + actions from composition
            final ObjectComposition baseComp = client.getObjectDefinition(id);
            final ObjectComposition comp = resolveObjectComposition(client, baseComp);
            String name = (comp != null) ? comp.getName() : null;
            String[] actions = (comp != null) ? comp.getActions() : null;
            
            final String nmLower = (name != null) ? name.toLowerCase() : "";
            if (qLower != null && !qLower.isEmpty() && !nmLower.equals(qLower)) return;

            final Map<String,Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("type", type);
            row.put("name", name);
            row.put("world", java.util.Map.of("x", wx, "y", wy, "p", plane));

            if (actions != null) {
                java.util.List<String> al = new java.util.ArrayList<>();
                for (String a : actions) if (a != null) al.add(a);
                row.put("actions", al);
            }

            // bounds/canvas center from convex hull if present
            if (hull != null) {
                final java.awt.Rectangle rb = hull.getBounds();
                if (rb != null) {
                    row.put("bounds", java.util.Map.of("x", rb.x, "y", rb.y, "width", rb.width, "height", rb.height));
                    row.put("canvas", java.util.Map.of("x", rb.x + rb.width / 2, "y", rb.y + rb.height / 2));
                    // orientation hint
                    row.put("orientation", (rb.width > rb.height) ? "horizontal" : "vertical");
                }
            }
            // fallback: tile center projection
            try {
                final LocalPoint lp = LocalPoint.fromWorld(client, wx, wy);
                if (lp != null) {
                    final net.runelite.api.Point pt = Perspective.localToCanvas(client, lp, plane);
                    if (pt != null) {
                        row.put("tileCanvas", java.util.Map.of("x", pt.getX(), "y", pt.getY()));
                    }
                }
            } catch (Exception ignored) {}

            out.add(row);
        }

        private void getAllNestedChildren(Widget parent, java.util.List<Widget> allChildren) {
            if (parent == null) return;
            
            // Get static children
            Widget[] staticChildren = parent.getStaticChildren();
            if (staticChildren != null) {
                for (Widget child : staticChildren) {
                    if (child != null) {
                        allChildren.add(child);
                        // Recursively get children of this child
                        getAllNestedChildren(child, allChildren);
                    }
                }
            }
            
            // Get dynamic children
            Widget[] dynamicChildren = parent.getDynamicChildren();
            if (dynamicChildren != null) {
                for (Widget child : dynamicChildren) {
                    if (child != null) {
                        allChildren.add(child);
                        // Recursively get children of this child
                        getAllNestedChildren(child, allChildren);
                    }
                }
            }
            
            // For IF3 widgets, also get nested children
            if (parent.isIf3()) {
                Widget[] nestedChildren = parent.getNestedChildren();
                if (nestedChildren != null) {
                    for (Widget child : nestedChildren) {
                        if (child != null) {
                            allChildren.add(child);
                            // Recursively get children of this child
                            getAllNestedChildren(child, allChildren);
                        }
                    }
                }
            }
        }

        private void collectChildWidgets(Widget parent, java.util.List<java.util.Map<String,Object>> children) {
            if (parent == null) return;
            
            try {
                // Get all nested children recursively
                java.util.List<Widget> allChildren = new java.util.ArrayList<>();
                getAllNestedChildren(parent, allChildren);
                
                // Convert to data format and filter for visible widgets only
                for (Widget child : allChildren) {
                    if (child != null) {
                        // Check visibility: not hidden AND has valid bounds AND valid canvas position
                        boolean visible = false;
                        java.awt.Rectangle bounds = null;
                        net.runelite.api.Point canvasLocation = null;
                        try {
                            bounds = child.getBounds();
                            canvasLocation = child.getCanvasLocation();
                            boolean isHidden = child.isHidden();
                            boolean hasValidBounds = (bounds != null && bounds.x >= 0 && bounds.y >= 0);
                            boolean hasValidCanvasLocation = (canvasLocation != null && canvasLocation.getX() >= 0 && canvasLocation.getY() >= 0);
                            visible = !isHidden && hasValidBounds && hasValidCanvasLocation;
                        } catch (Exception ignored) {}
                        
                        // Only add visible widgets
                        if (visible) {
                            java.util.Map<String,Object> childData = new java.util.LinkedHashMap<>();
                            childData.put("id", child.getId());
                            
                            // Get name safely
                            try {
                                String name = child.getName();
                                childData.put("name", name != null ? name : "");
                            } catch (Exception ignored) {
                                childData.put("name", "");
                            }
                            
                            // Get text safely
                            try {
                                String text = child.getText();
                                childData.put("text", text != null ? text : "");
                            } catch (Exception ignored) {
                                childData.put("text", "");
                            }
                            
                            childData.put("visible", visible);
                            childData.put("hasListener", child.hasListener());
                            childData.put("isIf3", child.isIf3());
                            
                            // Get sprite ID safely
                            try {
                                int spriteId = child.getSpriteId();
                                childData.put("spriteId", spriteId);
                            } catch (Exception ignored) {
                                childData.put("spriteId", -1);
                            }
                            
                            // Get item ID safely
                            try {
                                int itemId = child.getItemId();
                                childData.put("itemId", itemId);
                            } catch (Exception ignored) {
                                childData.put("itemId", -1);
                            }
                            
                            // Get OnOpListener safely
                            try {
                                Object onOpListener = child.getOnOpListener();
                                childData.put("onOpListener", onOpListener);
                            } catch (Exception ignored) {
                                childData.put("onOpListener", null);
                            }
                            
                            // Get text color safely
                            try {
                                int textColor = child.getTextColor();
                                childData.put("textColor", String.format("%x", textColor));
                            } catch (Exception ignored) {
                                childData.put("textColor", "");
                            }
                            
                            // Get bounds if available
                            if (bounds != null) {
                                childData.put("bounds", java.util.Map.of(
                                        "x", bounds.x,
                                        "y", bounds.y,
                                        "width", bounds.width,
                                        "height", bounds.height
                                ));
                            }
                            
                            // Get canvas location if available
                            if (canvasLocation != null) {
                                childData.put("canvasLocation", java.util.Map.of(
                                        "x", canvasLocation.getX(),
                                        "y", canvasLocation.getY()
                                ));
                            }
                            
                            children.add(childData);
                        }
                    }
                }
            } catch (Exception e) {
                System.out.println("[DEBUG] Error accessing child widgets: " + e.getMessage());
            }
        }


        // A tile is hard-blocked (solid object etc.)
        private static boolean hardBlocked(int[][] flags, int x, int y)
        {
            int f = flags[x][y];
            return (f & (BLOCK_MOVEMENT_FULL | BLOCK_MOVEMENT_OBJECT | BLOCK_MOVEMENT_FLOOR)) != 0;
        }

        // Can we legally move from (x,y) -> (nx,ny) given dx,dy in {-1,0,1}?
        private static boolean canStep(
                int[][] flags,
                boolean[][] doorHere,  // <— NEW
                int x, int y,
                int dx, int dy
        ) {
            final int nx = x + dx, ny = y + dy;
            if (!inScene(nx, ny)) return false;

            // If destination tile itself is hard-blocked, only allow if there's a door on either tile.
            if (!isWalkableLocal(flags, nx, ny)) {
                if (!(doorHere[x][y] || doorHere[nx][ny])) return false;
                // allow through if a door is present on source or dest tile
            }

            // Cardinal step: check the edge bits. If blocked, allow if door on either tile.
            if (dx == -1 && dy == 0) {
                boolean edgeBlocked =
                        ((flags[x][y] & net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_WEST) != 0) ||
                                ((flags[nx][ny] & net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_EAST) != 0);
                if (edgeBlocked && !(doorHere[x][y] || doorHere[nx][ny])) return false;
                return true;
            }
            if (dx == 1 && dy == 0) {
                boolean edgeBlocked =
                        ((flags[x][y] & net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_EAST) != 0) ||
                                ((flags[nx][ny] & net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_WEST) != 0);
                if (edgeBlocked && !(doorHere[x][y] || doorHere[nx][ny])) return false;
                return true;
            }
            if (dx == 0 && dy == 1) {
                boolean edgeBlocked =
                        ((flags[x][y] & net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_NORTH) != 0) ||
                                ((flags[nx][ny] & net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_SOUTH) != 0);
                if (edgeBlocked && !(doorHere[x][y] || doorHere[nx][ny])) return false;
                return true;
            }
            if (dx == 0 && dy == -1) {
                boolean edgeBlocked =
                        ((flags[x][y] & net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_SOUTH) != 0) ||
                                ((flags[nx][ny] & net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_NORTH) != 0);
                if (edgeBlocked && !(doorHere[x][y] || doorHere[nx][ny])) return false;
                return true;
            }

            // Diagonals require both adjacent cardinals to be valid steps (no corner cut).
            // We reuse canStep() so doors on either cardinal edge also soften those checks.
            // Additionally, check corner tiles that the diagonal path passes through.
            if (dx == 1 && dy == 1) {
                // Northeast diagonal: check cardinal moves AND corner tiles
                boolean cardinalValid = canStep(flags, doorHere, x, y, 1, 0) && canStep(flags, doorHere, x, y, 0, 1);
                if (!cardinalValid) return false;
                
                // Check corner tiles: (x+1, y) north wall and (x, y+1) east wall
                boolean corner1Blocked = (flags[x+1][y] & net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_NORTH) != 0;
                boolean corner2Blocked = (flags[x][y+1] & net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_EAST) != 0;
                return !(corner1Blocked || corner2Blocked);
            }
            if (dx == 1 && dy == -1) {
                // Southeast diagonal: check cardinal moves AND corner tiles
                boolean cardinalValid = canStep(flags, doorHere, x, y, 1, 0) && canStep(flags, doorHere, x, y, 0, -1);
                if (!cardinalValid) return false;
                
                // Check corner tiles: (x+1, y) south wall and (x, y-1) east wall
                boolean corner1Blocked = (flags[x+1][y] & net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_SOUTH) != 0;
                boolean corner2Blocked = (flags[x][y-1] & net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_EAST) != 0;
                return !(corner1Blocked || corner2Blocked);
            }
            if (dx == -1 && dy == 1) {
                // Northwest diagonal: check cardinal moves AND corner tiles
                boolean cardinalValid = canStep(flags, doorHere, x, y, -1, 0) && canStep(flags, doorHere, x, y, 0, 1);
                if (!cardinalValid) return false;
                
                // Check corner tiles: (x-1, y) north wall and (x, y+1) west wall
                boolean corner1Blocked = (flags[x-1][y] & net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_NORTH) != 0;
                boolean corner2Blocked = (flags[x][y+1] & net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_WEST) != 0;
                return !(corner1Blocked || corner2Blocked);
            }
            if (dx == -1 && dy == -1) {
                // Southwest diagonal: check cardinal moves AND corner tiles
                boolean cardinalValid = canStep(flags, doorHere, x, y, -1, 0) && canStep(flags, doorHere, x, y, 0, -1);
                if (!cardinalValid) return false;
                
                // Check corner tiles: (x-1, y) south wall and (x, y-1) west wall
                boolean corner1Blocked = (flags[x-1][y] & net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_SOUTH) != 0;
                boolean corner2Blocked = (flags[x][y-1] & net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_WEST) != 0;
                return !(corner1Blocked || corner2Blocked);
            }

            return false;
        }





        /* ======================= CMD STRUCT ======================= */

        private static final class Cmd
        {
            // Common
            String cmd;

            // click
            Integer x;
            Integer y;
            Integer plane;
            Integer button;
            @SerializedName("hover_only")
            Boolean hoverOnly;

            // key
            @SerializedName("k")
            String k;

            // type
            @SerializedName("text")      String text;
            @SerializedName("perCharMs") Integer perCharMs;

            // path
            @SerializedName("goalX") Integer goalX;
            @SerializedName("goalY") Integer goalY;
            @SerializedName("maxWps") Integer maxWps;
            @SerializedName("visualize") Boolean visualize;

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

            @SerializedName("amount") Integer amount;

            @SerializedName("key") String key;   // e.g. "LEFT", "RIGHT", "esc"
            
            // world hopping
            @SerializedName("world_id") Integer world_id;
            @SerializedName("ms")  Integer ms;   // hold duration in milliseconds

            @SerializedName("types") List<String> types; // e.g. ["WALL","GAME","DECOR"]
            @SerializedName("name")   String name;
            @SerializedName("exactMatch") Boolean exactMatch; // For exact vs contains matching

            // get-var
            @SerializedName("id")   Integer id;    // varbit/varp id
            @SerializedName("kind") String  kind;  // "varbit" (default) or "varp"
            
            // get-varc-int
            @SerializedName("var_id") Integer var_id;  // varc int id
            
            // widget commands
            @SerializedName("widget_id") Integer widget_id;  // widget ID for widget commands
            
            // door state commands
            @SerializedName("door_x") Integer door_x;
            @SerializedName("door_y") Integer door_y;
            @SerializedName("door_p") Integer door_p;


        }
    }
    

        private static int keyCodeFrom(String key) {
            if (key == null) return -1;
            
            switch (key.toUpperCase()) {
                case "ENTER": return java.awt.event.KeyEvent.VK_ENTER;
                case "ESC": case "ESCAPE": return java.awt.event.KeyEvent.VK_ESCAPE;
                case "BACKSPACE": return java.awt.event.KeyEvent.VK_BACK_SPACE;
                case "SPACE": case "SPACEBAR": return java.awt.event.KeyEvent.VK_SPACE;
                case "TAB": return java.awt.event.KeyEvent.VK_TAB;
                case "SHIFT": return java.awt.event.KeyEvent.VK_SHIFT;
                case "CTRL": case "CONTROL": return java.awt.event.KeyEvent.VK_CONTROL;
                case "ALT": return java.awt.event.KeyEvent.VK_ALT;
                case "UP": return java.awt.event.KeyEvent.VK_UP;
                case "DOWN": return java.awt.event.KeyEvent.VK_DOWN;
                case "LEFT": return java.awt.event.KeyEvent.VK_LEFT;
                case "RIGHT": return java.awt.event.KeyEvent.VK_RIGHT;
                case "F1": return java.awt.event.KeyEvent.VK_F1;
                case "F2": return java.awt.event.KeyEvent.VK_F2;
                case "F3": return java.awt.event.KeyEvent.VK_F3;
                case "F4": return java.awt.event.KeyEvent.VK_F4;
                case "F5": return java.awt.event.KeyEvent.VK_F5;
                case "F6": return java.awt.event.KeyEvent.VK_F6;
                case "F7": return java.awt.event.KeyEvent.VK_F7;
                case "F8": return java.awt.event.KeyEvent.VK_F8;
                case "F9": return java.awt.event.KeyEvent.VK_F9;
                case "F10": return java.awt.event.KeyEvent.VK_F10;
                case "F11": return java.awt.event.KeyEvent.VK_F11;
                case "F12": return java.awt.event.KeyEvent.VK_F12;
                default:
                    // For single character keys, return the character code
                    if (key.length() == 1) {
                        return java.awt.event.KeyEvent.getExtendedKeyCodeForChar(key.charAt(0));
                    }
                    return -1;
            }
        }

        private static String getTabName(int tabIndex) {
        switch (tabIndex) {
            case 0: return "COMBAT";              // STONE0
            case 1: return "SKILLS";              // STONE1
            case 2: return "QUESTS";              // STONE2
            case 3: return "INVENTORY";           // STONE3
            case 4: return "EQUIPMENT";           // STONE4
            case 5: return "PRAYER";              // STONE5
            case 6: return "SPELLBOOK";           // STONE6
            case 7: return "CHAT-CHANNEL";        // STONE7
            case 8: return "ACCOUNT_MANAGEMENT";  // STONE8
            case 9: return "FRIENDS_LIST";        // STONE9
            case 10: return "SETTINGS";           // STONE11 (STONE10 skipped)
            case 11: return "EMOTES";             // STONE12
            case 12: return "MUSIC";              // STONE13
            case -1: return "UNKNOWN";            // Tab detection not working
            default: return "UNKNOWN";
        }
    }

    // Helper methods for tutorial widget data
    private static java.util.Map<String, Object> widgetBoundsJson(Widget w) {
        if (w == null) {
            return null;
        }

        java.util.Map<String, Object> out = new java.util.HashMap<>();

        // AWT bounds rectangle
        Rectangle r = w.getBounds();
        if (r != null) {
            java.util.Map<String, Object> rect = new java.util.HashMap<>();
            rect.put("x", r.x);
            rect.put("y", r.y);
            rect.put("width", r.width);
            rect.put("height", r.height);
            out.put("bounds", rect);
        } else {
            out.put("bounds", null);
        }

        return out;
    }

    private static String safeText(Widget w) {
        if (w == null) {
            return "";
        }
        try {
            String t = w.getText();
            return (t != null) ? t : "";
        } catch (Exception ignored) {
            return "";
        }
    }
}
