package net.runelite.client.plugins.stateexporter2;

import com.google.inject.Provides;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.Point;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;

import net.runelite.client.util.Text;  // for stripping <col=...> etc
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseListener;
import java.awt.event.MouseEvent;
import net.runelite.client.input.MouseManager;
import javax.inject.Inject;
import java.awt.Rectangle;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import net.runelite.client.callback.ClientThread;

@Slf4j
@PluginDescriptor(
    name = "State Exporter 2"
)
public class StateExporter2Plugin extends Plugin
{
    private static final int WIDGET_CRAFTING_INTERFACE = 29229056;
    private static final int WIDGET_MAKE_SAPPHIRE_RINGS = 29229065;
    private static final int WIDGET_MAKE_EMERALD_RINGS = 29229066;
    private static final int WIDGET_MAKE_GOLD_RINGS = 29229064;

    // Logging system
    @Inject
    private Client client;

    @Inject
    private StateExporter2Config config;

    @Inject
    private ConfigManager configManager;

    @Inject
    private ItemManager itemManager;


    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    @Inject
    private ClientThread clientThread;

    private ScheduledExecutorService executor;
    private ScheduledFuture<?> exportFuture;

    @Inject
    private MouseManager mouseManager;

    private MouseListener mouseListener;
    private Point lastClickCanvas = null;
    private long lastClickEpochMs = 0L;


    // Action history for RL (store last action taken)
    private Map<String, Object> lastInteraction = null;

    @Provides
    StateExporter2Config provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(StateExporter2Config.class);
    }

    @Override
    protected void startUp() throws Exception
    {
        log.info("State Exporter 2 started");
        // single-thread executor for periodic export
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "stateexporter2-export");
            t.setDaemon(true);
            return t;
        });
        // start at configured interval
        scheduleExportTask(config.exportIntervalMs());

        mouseListener = new MouseAdapter()
        {
            @Override
            public MouseEvent mousePressed(MouseEvent e)
            {
                lastClickCanvas = new Point(e.getX(), e.getY());
                lastClickEpochMs = System.currentTimeMillis();
                return e; // IMPORTANT: return the event (don’t consume it)
            }
        };
        mouseManager.registerMouseListener(mouseListener);
    }


    @Override
    protected void shutDown() throws Exception
    {
        log.info("State Exporter 2 stopped");
        if (exportFuture != null)
        {
            exportFuture.cancel(true);
            exportFuture = null;
        }
        if (executor != null)
        {
            executor.shutdownNow();
            executor = null;
        }
        if (mouseListener != null)
        {
            mouseManager.unregisterMouseListener(mouseListener);
            mouseListener = null;
        }
        lastClickCanvas = null;
        lastClickEpochMs = 0L;

    }



    @Subscribe
    public void onGameTick(GameTick event)
    {
        // Keep the "Refresh Directory" button behavior
        if (config.refreshDirectory())
        {
            refreshGamestatesDirectory();
            config.setRefreshDirectory(false);
        }
    }


    @Subscribe
    public void onConfigChanged(ConfigChanged e)
    {
        if (!"stateexporter2".equals(e.getGroup()))
        {
            return;
        }
        if ("exportIntervalMs".equals(e.getKey()))
        {
            scheduleExportTask(config.exportIntervalMs());
        }
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
            Point mousePos = client.getMouseCanvasPosition();
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

    private void pruneGamestateFiles(File dir, int maxToKeep)
    {
        if (dir == null || !dir.isDirectory() || maxToKeep <= 0)
        {
            return;
        }
        File[] files = dir.listFiles((d, name) -> name != null && name.endsWith(".json"));
        if (files == null || files.length <= maxToKeep)
        {
            return;
        }
        // sort newest -> oldest by last modified
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = maxToKeep; i < files.length; i++)
        {
            try
            {
                if (!files[i].delete())
                {
                    log.debug("Could not delete old gamestate file: {}", files[i].getAbsolutePath());
                }
            }
            catch (SecurityException se)
            {
                log.warn("Delete blocked for file: {}", files[i].getAbsolutePath(), se);
            }
        }
    }


    /**
     * Export gamestate JSON with extracted data
     */
    private void exportGamestate()
    {
        try
        {
            // Create gamestate with extracted data
            Map<String, Object> gamestate = new HashMap<>();
            gamestate.put("timestamp", System.currentTimeMillis());
            gamestate.put("plugin", "StateExporter2");
            gamestate.put("version", "1.0.0");
            
            // Extract game state data
            Map<String, Object> gameData = extractGameData();
            gamestate.put("data", gameData);

            Map<String, Object> ge = null;
            try {
                Object maybe = gameData.get("grand_exchange");
                if (maybe instanceof Map) {
                    // unchecked cast ok for our usage here
                    ge = (Map<String, Object>) maybe;
                }
            } catch (Exception ignored) {}

            if (ge == null) {
                ge = new HashMap<>();
            }

            // add/overwrite ONLY the location fields; keep open/widgets that extractGameData() populated
            WorldPoint geCenter = new WorldPoint(3165, 3487, 0);
            ge.put("worldX", geCenter.getX());
            ge.put("worldY", geCenter.getY());
            ge.put("plane",  geCenter.getPlane());

            // put the merged map back
            gameData.put("grand_exchange", ge);

            Map<String, Object> lastClick = new HashMap<>();
            if (lastClickCanvas != null)
            {
                lastClick.put("canvasX", lastClickCanvas.getX());
                lastClick.put("canvasY", lastClickCanvas.getY());
                lastClick.put("epochMs", lastClickEpochMs);
                lastClick.put("sinceMs", Math.max(0L, System.currentTimeMillis() - lastClickEpochMs));
            }
            else
            {
                lastClick.put("canvasX", null);
                lastClick.put("canvasY", null);
                lastClick.put("epochMs", null);
                lastClick.put("sinceMs", null);
            }
            gameData.put("lastClick", lastClick);

            // Get the gamestates directory
            String gamestatesDir = config.gamestatesDirectory();
            
            // Create directory if it doesn't exist
            File dir = new File(gamestatesDir);
            if (!dir.exists())
            {
                boolean created = dir.mkdirs();
                if (!created)
                {
                    log.error("Failed to create gamestates directory: {}", gamestatesDir);
                    return;
                }
            }

            ensureRoomForOne(dir, config.maxGamestateFiles());
            // Create filename with timestamp
            String filename = System.currentTimeMillis() + ".json";
            File file = new File(dir, filename);

            // Write JSON to file
            try (FileWriter writer = new FileWriter(file))
            {
                gson.toJson(gamestate, writer);
                log.debug("Exported gamestate to: {}", file.getAbsolutePath());
                ensureRoomForOne(dir, config.maxGamestateFiles());
                pruneGamestateFiles(dir, config.maxGamestateFiles());
            }

        }
        catch (IOException e)
        {
            log.error("Failed to export gamestate: {}", e.getMessage());
        }
        catch (Exception e)
        {
            log.error("Unexpected error during gamestate export: {}", e.getMessage());
        }
    }

    /**
     * Extract game data using the same logic as your shell script
     */
    private Map<String, Object> extractGameData()
    {
        Map<String, Object> data = new HashMap<>();
        
        try
        {
            // Basic game state
            data.put("gameState", client.getGameState().toString());
            data.put("currentWorld", client.getWorld());
            
            // Player info
            Player localPlayer = client.getLocalPlayer();
            if (localPlayer != null)
            {
                Map<String, Object> playerInfo = new HashMap<>();
                WorldPoint worldPos = localPlayer.getWorldLocation();
                playerInfo.put("worldX", worldPos.getX());
                playerInfo.put("worldY", worldPos.getY());
                playerInfo.put("plane", worldPos.getPlane());
                playerInfo.put("animation", localPlayer.getAnimation());
                playerInfo.put("orientation", localPlayer.getOrientation());
                data.put("player", playerInfo);
            }
            
            // Mouse and hovered tile info
            Point mousePos = client.getMouseCanvasPosition();
            Map<String, Object> mouseInfo = new HashMap<>();
            mouseInfo.put("canvasX", mousePos.getX());
            mouseInfo.put("canvasY", mousePos.getY());
            data.put("mouse", mouseInfo);
            
            Tile hoveredTile = client.getSelectedSceneTile();
            if (hoveredTile != null)
            {
                Map<String, Object> tileInfo = new HashMap<>();
                WorldPoint worldPos = hoveredTile.getWorldLocation();
                tileInfo.put("worldX", worldPos.getX());
                tileInfo.put("worldY", worldPos.getY());
                tileInfo.put("plane", worldPos.getPlane());
                
                // Game objects on hovered tile
                GameObject[] hoveredObjects = hoveredTile.getGameObjects();
                List<Map<String, Object>> gameObjects = new ArrayList<>();
                
                for (int i = 0; i < hoveredObjects.length; i++)
                {
                    GameObject object = hoveredObjects[i];
                    if (object != null)
                    {
                        Map<String, Object> objInfo = new HashMap<>();
                        objInfo.put("index", i);
                        objInfo.put("id", object.getId());
                        
                        try
                        {
                            ObjectComposition objDef = client.getObjectDefinition(object.getId());
                            objInfo.put("name", objDef.getName());
                            objInfo.put("actions", objDef.getActions());
                        }
                        catch (Exception e)
                        {
                            objInfo.put("name", "Unknown");
                            objInfo.put("actions", new String[0]);
                        }
                        
                        WorldPoint objectPos = object.getWorldLocation();
                        objInfo.put("worldX", objectPos.getX());
                        objInfo.put("worldY", objectPos.getY());
                        objInfo.put("plane", objectPos.getPlane());
                        objInfo.put("canvasX", object.getCanvasLocation().getX());
                        objInfo.put("canvasY", object.getCanvasLocation().getY());
                        
                        try
                        {
                            Rectangle objectRect = object.getClickbox().getBounds();
                            Map<String, Object> clickbox = new HashMap<>();
                            clickbox.put("x", objectRect.x);
                            clickbox.put("y", objectRect.y);
                            clickbox.put("width", objectRect.width);
                            clickbox.put("height", objectRect.height);
                            objInfo.put("clickbox", clickbox);
                        }
                        catch (Exception e)
                        {
                            objInfo.put("clickbox", null);
                        }
                        
                        gameObjects.add(objInfo);
                    }
                }
                
                tileInfo.put("gameObjects", gameObjects);
                data.put("hoveredTile", tileInfo);
            }
            else
            {
                data.put("hoveredTile", null);
            }
            
            // Closest NPCs
            data.put("closestNPCs", getClosestNPCs());
            
            // Closest Game Objects
            data.put("closestGameObjects", getClosestGameObjects());

            // === BEGIN: GE booth scan (verbatim-style from your dev shell) ===
            log.info("Hello {}", client.getGameState());

            final String needle = "grand exchange";

            List<Map<String, Object>> geBooths = new ArrayList<>();

            Tile[][] planeTiles = client.getScene().getTiles()[client.getPlane()];
            if (planeTiles != null) {
                for (Tile[] row : planeTiles) {
                    if (row == null) continue;
                    for (Tile tile : row) {
                        if (tile == null) continue;

                        // Helper to test a TileObject by id -> name (same as shell; using log.info)
                        java.util.function.Consumer<net.runelite.api.TileObject> check = (to) -> {
                            if (to == null) return;
                            ObjectComposition def = client.getObjectDefinition(to.getId());
                            if (def == null) return;
                            String name = def.getName();
                            if (name != null && name.toLowerCase().contains(needle)) {

                                // Collect into gamestate JSON (minimal shape)
                                Map<String, Object> m = new HashMap<>();
                                m.put("id", to.getId());
                                m.put("name", name);
                                m.put("type", to.getClass().getSimpleName());
                                m.put("worldX", to.getWorldLocation().getX());
                                m.put("worldY", to.getWorldLocation().getY());

                                // Canvas fallback (keep it super simple)
                                Point canvasPt = to.getCanvasLocation();
                                if (canvasPt != null) {
                                    m.put("canvasX", canvasPt.getX());
                                    m.put("canvasY", canvasPt.getY());
                                } else {
                                    m.put("canvasX", -1);
                                    m.put("canvasY", -1);
                                }

                                // Clickbox if available (GameObject only); ignore errors
                                try {
                                    if (to instanceof GameObject) {
                                        Rectangle r = ((GameObject) to).getClickbox().getBounds();
                                        Map<String, Integer> cb = new HashMap<>();
                                        cb.put("x", r.x);
                                        cb.put("y", r.y);
                                        cb.put("width", r.width);
                                        cb.put("height", r.height);
                                        m.put("clickbox", cb);
                                    } else {
                                        m.put("clickbox", null);
                                    }
                                } catch (Exception e) {
                                    m.put("clickbox", null);
                                }

                                geBooths.add(m);
                            }
                        };

                        // Game objects (most interactables, incl. GE booth)
                        GameObject[] gos = tile.getGameObjects();
                        if (gos != null) for (GameObject go : gos) check.accept(go);

                        // Walls / deco / ground (just in case)
                        check.accept(tile.getWallObject());
                        check.accept(tile.getDecorativeObject());
                        check.accept(tile.getGroundObject());
                    }
                }
            }

            data.put("ge_booths", geBooths);
            // === END: GE booth scan ===

            // ---- Grand Exchange widgets/state ----
            Map<String, Object> ge = new HashMap<>();
            Widget geRoot = client.getWidget(30474240); // root container you mentioned
            boolean geOpen = (geRoot != null);
            ge.put("open", geOpen);
            ge.put("rootId", 30474240);

            // Flat widget map keyed by id (easy lookups by your Python plan)
            Map<String, Object> geWidgets = new HashMap<>();
            int[] ids = new int[]{
                    30474240,          // GE root
                    30474244,          // Offer panel exists check (your "post confirm" indicator)
                    30474246,           // <-- Collect lives under here on some layouts
                    30474266,          // shared id for (-5%), (+X%), Confirm, Qty box, etc.
                    30474247,30474248,30474249,30474250,30474251,30474252,30474253,30474254 // offer slots

            };
            for (int id : ids) putWidgetIfPresent(geWidgets, id, client);

            for (int id : new int[]{30474246, 30474266,
                    30474247,30474248,30474249,30474250,30474251,30474252,30474253,30474254})
            {
                try {
                    Widget w = client.getWidget(id);
                    if (w != null && !w.isHidden() && w.getChildren() != null) {  // <—
                        int idx = 0;
                        for (Widget c : w.getChildren()) {
                            if (c == null || c.isHidden()) { idx++; continue; }    // <—
                            Map<String, Object> cj = widgetFlatJson(c);
                            if (cj != null) {
                                // optionally discard invisible/zero-size children
                                Map<String, Object> b = (Map<String, Object>) cj.get("bounds");
                                if (b != null &&
                                        ((Number)b.get("width")).intValue() > 0 &&
                                        ((Number)b.get("height")).intValue() > 0)
                                {
                                    geWidgets.put(id + ":" + idx, cj);
                                }
                            }
                            idx++;
                        }
                    }
                } catch (Exception ignored) {}
            }



            ge.put("widgets", geWidgets);
            data.put("grand_exchange", ge);

            // ---- GE "inventory" panel (appears while GE is open) ----
            // Parent container for items shown while GE is open: 30605312
            Map<String, Object> geInv = new HashMap<>();
            Widget geInvParent = client.getWidget(30605312);
            if (geInvParent != null && geInvParent.getChildren() != null) {
                List<Map<String, Object>> items = new ArrayList<>();
                for (Widget c : geInvParent.getChildren()) {
                    if (c == null) continue;
                    Map<String, Object> cj = geInvChildJson(c);
                    if (cj != null) {
                        // Optional: only keep actual item-like entries (either has itemId>0 or a non-empty stripped name)
                        Integer iid = (Integer) cj.getOrDefault("itemId", -1);
                        String ns = (String) cj.get("nameStripped");
                        String ts = (String) cj.get("textStripped");
                        if ((iid != null && iid > 0) || (ns != null && !ns.isEmpty()) || (ts != null && !ts.isEmpty())) {
                            items.add(cj);
                        }
                    }
                }
                geInv.put("parentId", 30605312);
                geInv.put("items", items); // each item has {id, text/name raw+stripped, itemId/qty(if any), bounds}
            }
            // Always put the key so your consumer code can branch safely
            data.put("ge_inventory", geInv);


            // ---- Inventory slot widget bounds (for "Offer" clicks) ----
            Map<String, Object> invWidgets = new HashMap<>();
            try {
                Widget inv = client.getWidget(WidgetInfo.INVENTORY);
                if (inv != null && inv.getChildren() != null) {
                    Widget[] kids = inv.getChildren();
                    for (int slot = 0; slot < kids.length; slot++) {
                        Widget c = kids[slot];
                        if (c == null) continue;
                        Map<String, Object> wj = widgetFlatJson(c);
                        if (wj != null) invWidgets.put(String.valueOf(slot), wj);
                    }
                }
            } catch (Exception ignored) {}
            data.put("inventory_widgets", invWidgets);

            // ---- (optional) simple price cache for items you care about ----
            Map<String, Integer> gePrices = new HashMap<>();
            try {
                // You can add more here or wire a request list; keep simple as scaffolding
                gePrices.put("Sapphire", itemManager.getItemPrice(1607));
                gePrices.put("Emerald",  itemManager.getItemPrice(1605));
                gePrices.put("Gold bar", itemManager.getItemPrice(2357));
                // rings (noted or not) if you want to log them:
                gePrices.put("Sapphire ring", itemManager.getItemPrice(1637));
                gePrices.put("Emerald ring",  itemManager.getItemPrice(1639));
            } catch (Exception ignored) {}
            data.put("ge_prices", gePrices);


            // Menu entries
            MenuEntry[] menuEntries = client.getMenuEntries();
            List<Map<String, Object>> menuInfo = new ArrayList<>();
            for (int i = 0; i < menuEntries.length; i++)
            {
                MenuEntry entry = menuEntries[i];
                if (entry != null)
                {
                    Map<String, Object> menuEntry = new HashMap<>();
                    menuEntry.put("index", i);
                    menuEntry.put("option", entry.getOption());
                    menuEntry.put("target", entry.getTarget());
                    menuInfo.add(menuEntry);
                }
            }
            data.put("menuEntries", menuInfo);
            
           // ---- Bank export ----
            Map<String, Object> bank = new HashMap<>();

            // Widgets: bank root + items container
            Widget wBankRoot = client.getWidget(WidgetInfo.BANK_CONTAINER);          // bank main panel
            Widget wBankItems = client.getWidget(WidgetInfo.BANK_ITEM_CONTAINER);    // grid of item slots

            // Item container for bank contents
            ItemContainer bankContainer = client.getItemContainer(InventoryID.BANK);

            // Decide "open" explicitly: either the widgets exist or the container exists
            boolean bankOpen = (wBankRoot != null) || (wBankItems != null) || (bankContainer != null);
            bank.put("bankOpen", bankOpen);

            // Always record bounds (null if widget missing)
            bank.put("bankInterfaceBounds", widgetBoundsJson(wBankRoot));

            List<Map<String, Object>> bankSlots = new ArrayList<>();
            int totalItems = 0;
            int emptySlots = 0;
            int totalSlots = 0;

            if (bankContainer != null)
            {
                Item[] items = bankContainer.getItems();
                totalSlots = (items != null) ? items.length : 0;

                // Children of the items container correspond to visual slot widgets
                Widget[] itemChildren = (wBankItems != null) ? wBankItems.getChildren() : null;

                for (int slotId = 0; slotId < totalSlots; slotId++)
                {
                    Item item = items[slotId];
                    boolean isFilled = (item != null && item.getId() != -1 && item.getQuantity() > 0);

                    Map<String, Object> slot = new HashMap<>();
                    slot.put("slotId", slotId);

                    if (isFilled)
                    {
                        totalItems++;
                        int itemId = item.getId();
                        int qty = item.getQuantity();

                        String itemName = "Unknown";
                        try {
                            ItemComposition comp = client.getItemDefinition(itemId);
                            itemName = (comp != null) ? comp.getName() : "Unknown";
                        } catch (Exception ignored) {}

                        // Bounds from the corresponding child widget if present
                        Map<String, Object> boundsJson = null;
                        if (itemChildren != null && slotId < itemChildren.length && itemChildren[slotId] != null)
                        {
                            boundsJson = widgetBoundsJson(itemChildren[slotId]);
                        }

                        slot.put("itemId", itemId);
                        slot.put("itemName", itemName);
                        slot.put("quantity", qty);
                        slot.put("bounds", boundsJson);   // may be null if widget not available
                    }
                    else
                    {
                        emptySlots++;
                        slot.put("itemId", -1);
                        slot.put("itemName", null);
                        slot.put("quantity", 0);
                        slot.put("bounds", null);
                    }

                    bankSlots.add(slot);
                }
            }
            else
            {
                // Bank closed or unavailable
                bank.put("bankOpen", false);
                bankSlots = new ArrayList<>();
                totalItems = 0;
                emptySlots = 0;
                totalSlots = 0;
            }

            bank.put("slots", bankSlots);
            bank.put("totalItems", totalItems);
            bank.put("emptySlots", emptySlots);
            bank.put("totalSlots", totalSlots);

            // Attach to your export payload
            data.put("bank", bank);

            // ---- Bank control widgets (withdraw as note / quantity all) ----
            try {
                Widget wNoteToggle = client.getWidget(786458);   // "Withdraw as note"
                Widget wQtyAll     = client.getWidget(786470);   // "Quantity: All"

                Map<String, Object> bankWidgets = new HashMap<>();
                bankWidgets.put("withdraw_note_toggle", widgetBoundsJson(wNoteToggle));
                bankWidgets.put("withdraw_quantity_all", widgetBoundsJson(wQtyAll));

                data.put("bank_widgets", bankWidgets);
            } catch (Exception e) {
                // Keep JSON stable even if widgets are missing
                Map<String, Object> bankWidgets = new HashMap<>();
                bankWidgets.put("withdraw_note_toggle", null);
                bankWidgets.put("withdraw_quantity_all", null);
                data.put("bank_widgets", bankWidgets);
            }


            // Inventory detailed slot information
            Map<String, Object> inventoryInfo = new HashMap<>();
            ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);

            if (inventory != null)
            {
                Item[] items = inventory.getItems();
                List<Map<String, Object>> inventorySlots = new ArrayList<>();
                totalItems = 0;
                emptySlots = 0;

                // Root inventory widget (optional, but handy to keep)
                Widget inventoryWidget = client.getWidget(WidgetInfo.INVENTORY);

                for (int slotId = 0; slotId < items.length; slotId++)
                {
                    Item item = items[slotId];
                    Map<String, Object> slotInfo = new HashMap<>();
                    slotInfo.put("slotId", slotId);

                    if (item != null && item.getId() != -1)
                    {
                        totalItems++;

                        try
                        {
                            ItemComposition itemDef = client.getItemDefinition(item.getId());
                            slotInfo.put("itemId", item.getId());
                            slotInfo.put("itemName", itemDef.getName());
                            slotInfo.put("quantity", item.getQuantity());

                            // Bounds via helper (no canvasX/canvasY)
                            Map<String, Object> boundsJson = null;
                            if (inventoryWidget != null)
                            {
                                Widget itemWidget = inventoryWidget.getChild(slotId);
                                if (itemWidget != null)
                                {
                                    boundsJson = widgetBoundsJson(itemWidget);
                                }
                            }
                            slotInfo.put("bounds", boundsJson); // null if not found
                        }
                        catch (Exception e)
                        {
                            slotInfo.put("itemId", item.getId());
                            slotInfo.put("itemName", "Unknown");
                            slotInfo.put("quantity", item.getQuantity());
                            slotInfo.put("bounds", null);
                        }
                    }
                    else
                    {
                        emptySlots++;
                        slotInfo.put("itemId", -1);
                        slotInfo.put("itemName", null);
                        slotInfo.put("quantity", 0);
                        slotInfo.put("bounds", null);
                    }

                    inventorySlots.add(slotInfo);
                }

                inventoryInfo.put("slots", inventorySlots);
                inventoryInfo.put("totalItems", totalItems);
                inventoryInfo.put("emptySlots", emptySlots);
                inventoryInfo.put("totalSlots", 28);
            }
            else
            {
                inventoryInfo.put("slots", new ArrayList<>());
                inventoryInfo.put("totalItems", 0);
                inventoryInfo.put("emptySlots", 28);
                inventoryInfo.put("totalSlots", 28);
            }

            data.put("inventory", inventoryInfo);

            data.put("tiles_15x15", collectTiles15x15());

            // ---- Crafting widgets: export bounds (or null if missing)
            Widget wCrafting = client.getWidget(WIDGET_CRAFTING_INTERFACE);
            Widget wMakeSapphireRing = client.getWidget(WIDGET_MAKE_SAPPHIRE_RINGS);
            Widget wMakeEmeraldRing = client.getWidget(WIDGET_MAKE_EMERALD_RINGS);
            Widget wMakeGoldRing = client.getWidget(WIDGET_MAKE_GOLD_RINGS);
            Map<String, Object> craftingWidgets = new HashMap<>();
            craftingWidgets.put("crafting_interface", widgetBoundsJson(wCrafting));
            craftingWidgets.put("make_sapphire_rings", widgetBoundsJson(wMakeSapphireRing));
            craftingWidgets.put("make_emerald_rings", widgetBoundsJson(wMakeEmeraldRing));
            craftingWidgets.put("make_gold_rings", widgetBoundsJson(wMakeGoldRing));
            data.put("crafting_widgets", craftingWidgets);

             // Crafting interface status - simplified check
             boolean craftingInterfaceOpen = (wCrafting != null);
             data.put("craftingInterfaceOpen", craftingInterfaceOpen);
            
            // Last interaction data
            if (lastInteraction != null) {
                data.put("lastInteraction", lastInteraction);
            }
            
        }
        catch (Exception e)
        {
            log.error("Error extracting game data: {}", e.getMessage());
            data.put("error", e.getMessage());
        }
        
        return data;
    }

    /**
     * Get the closest NPCs based on configuration
     */
    private List<Map<String, Object>> getClosestNPCs()
    {
        List<Map<String, Object>> npcList = new ArrayList<>();
        
        try
        {
            Player localPlayer = client.getLocalPlayer();
            if (localPlayer == null)
            {
                return npcList;
            }
            
            WorldPoint playerPos = localPlayer.getWorldLocation();
            List<String> prioritizedNames = getPrioritizedNames(config.prioritizedNPCs());
            
            // Get all NPCs and calculate distances
            List<NPC> allNPCs = new ArrayList<>();
            for (NPC npc : client.getNpcs())
            {
                if (npc != null && npc.getName() != null)
                {
                    allNPCs.add(npc);
                }
            }
            
            // Sort by distance to player
            allNPCs.sort((npc1, npc2) -> {
                WorldPoint pos1 = npc1.getWorldLocation();
                WorldPoint pos2 = npc2.getWorldLocation();
                
                double dist1 = playerPos.distanceTo(pos1);
                double dist2 = playerPos.distanceTo(pos2);
                
                return Double.compare(dist1, dist2);
            });
            
            // Add prioritized NPCs first
            for (NPC npc : allNPCs)
            {
                if (prioritizedNames.contains(npc.getName()) && npcList.size() < config.maxNPCs())
                {
                    npcList.add(createNPCInfo(npc, playerPos));
                }
            }
            
            // Add remaining closest NPCs
            for (NPC npc : allNPCs)
            {
                if (!prioritizedNames.contains(npc.getName()) && npcList.size() < config.maxNPCs())
                {
                    npcList.add(createNPCInfo(npc, playerPos));
                }
            }
        }
        catch (Exception e)
        {
            log.error("Error getting closest NPCs: {}", e.getMessage());
        }
        
        return npcList;
    }
    
    /**
     * Get the closest Game Objects based on configuration
     */
    private List<Map<String, Object>> getClosestGameObjects()
    {
        List<Map<String, Object>> objectList = new ArrayList<>();
        
        try
        {
            Player localPlayer = client.getLocalPlayer();
            if (localPlayer == null)
            {
                return objectList;
            }
            
            WorldPoint playerPos = localPlayer.getWorldLocation();
            List<String> prioritizedNames = getPrioritizedNames(config.prioritizedGameObjects());
            
            // Get all game objects and calculate distances
            List<GameObject> allObjects = new ArrayList<>();
            for (int x = 0; x < 104; x++)
            {
                for (int y = 0; y < 104; y++)
                {
                    Tile tile = client.getScene().getTiles()[client.getPlane()][x][y];
                    if (tile != null)
                    {
                        GameObject[] objects = tile.getGameObjects();
                        for (GameObject obj : objects)
                        {
                            if (obj != null)
                            {
                                try
                                {
                                    ObjectComposition objDef = client.getObjectDefinition(obj.getId());
                                    if (objDef != null && objDef.getName() != null && !objDef.getName().equals("null"))
                                    {
                                        allObjects.add(obj);
                                    }
                                }
                                catch (Exception e)
                                {
                                    // Ignore objects with invalid definitions
                                }
                            }
                        }
                    }
                }
            }
            
            // Sort by distance to player
            allObjects.sort((obj1, obj2) -> {
                WorldPoint pos1 = obj1.getWorldLocation();
                WorldPoint pos2 = obj2.getWorldLocation();
                
                double dist1 = playerPos.distanceTo(pos1);
                double dist2 = playerPos.distanceTo(pos2);
                
                return Double.compare(dist1, dist2);
            });
            
            // Add prioritized objects first
            for (GameObject obj : allObjects)
            {
                try
                {
                    ObjectComposition objDef = client.getObjectDefinition(obj.getId());
                    if (objDef != null && prioritizedNames.contains(objDef.getName()) && objectList.size() < config.maxGameObjects())
                    {
                        objectList.add(createGameObjectInfo(obj, playerPos));
                    }
                }
                catch (Exception e)
                {
                    // Ignore objects with invalid definitions
                }
            }
            
            // Add remaining closest objects
            for (GameObject obj : allObjects)
            {
                try
                {
                    ObjectComposition objDef = client.getObjectDefinition(obj.getId());
                    if (objDef != null && !prioritizedNames.contains(objDef.getName()) && objectList.size() < config.maxGameObjects())
                    {
                        objectList.add(createGameObjectInfo(obj, playerPos));
                    }
                }
                catch (Exception e)
                {
                    // Ignore objects with invalid definitions
                }
            }
        }
        catch (Exception e)
        {
            log.error("Error getting closest game objects: {}", e.getMessage());
        }
        
        return objectList;
    }
    
    /**
     * Create NPC info map
     */
    private Map<String, Object> createNPCInfo(NPC npc, WorldPoint playerPos)
    {
        Map<String, Object> npcInfo = new HashMap<>();
        WorldPoint npcPos = npc.getWorldLocation();
        
        npcInfo.put("id", npc.getId());
        npcInfo.put("name", npc.getName());
        npcInfo.put("worldX", npcPos.getX());
        npcInfo.put("worldY", npcPos.getY());
        npcInfo.put("plane", npcPos.getPlane());
        npcInfo.put("distance", playerPos.distanceTo(npcPos));
        
        try
        {
            NPCComposition npcDef = client.getNpcDefinition(npc.getId());
            npcInfo.put("actions", npcDef.getActions());
            LocalPoint lp = LocalPoint.fromWorld(client, npcPos);
            Point c = (lp != null) ? Perspective.localToCanvas(client, lp, npcPos.getPlane()) : null;
            npcInfo.put("canvasX", c != null ? c.getX() : -1);
            npcInfo.put("canvasY", c != null ? c.getY() : -1);
        }
        catch (Exception e)
        {
            npcInfo.put("actions", new String[0]);
        }
        
        return npcInfo;
    }

    // --- GE constants: confirmed from your shell output ---
    private static final int GE_BOOTH_ID_A = 10060;
    private static final int GE_BOOTH_ID_B = 10061;

    // Minimal “tile object → info” for GameObject/TileObject (mirrors your createGameObjectInfo)
    private Map<String, Object> createTileObjectInfo(TileObject to, WorldPoint playerPos)
    {
        Map<String, Object> objInfo = new HashMap<>();
        WorldPoint objPos = to.getWorldLocation();

        objInfo.put("id", to.getId());
        objInfo.put("worldX", objPos.getX());
        objInfo.put("worldY", objPos.getY());
        objInfo.put("plane", objPos.getPlane());
        objInfo.put("distance", playerPos.distanceTo(objPos));

        try {
            ObjectComposition objDef = client.getObjectDefinition(to.getId());
            objInfo.put("name", (objDef != null) ? objDef.getName() : "Unknown");
            objInfo.put("actions", (objDef != null) ? objDef.getActions() : new String[0]);

            // Canvas (fallback targeting)
            Point canvasPos = to.getCanvasLocation();
            objInfo.put("canvasX", canvasPos != null ? canvasPos.getX() : -1);
            objInfo.put("canvasY", canvasPos != null ? canvasPos.getY() : -1);

            // Clickbox when available (GameObject only)
            try {
                if (to instanceof GameObject) {
                    Rectangle r = ((GameObject) to).getClickbox().getBounds();
                    Map<String, Object> clickbox = new HashMap<>();
                    clickbox.put("x", r.x);
                    clickbox.put("y", r.y);
                    clickbox.put("width", r.width);
                    clickbox.put("height", r.height);
                    objInfo.put("clickbox", clickbox);
                } else {
                    objInfo.put("clickbox", null);
                }
            } catch (Exception e) {
                objInfo.put("clickbox", null);
            }
        } catch (Exception e) {
            objInfo.put("name", "Unknown");
            objInfo.put("actions", new String[0]);
            objInfo.put("canvasX", -1);
            objInfo.put("canvasY", -1);
            objInfo.put("clickbox", null);
        }

        return objInfo;
    }

    // Scan the current plane for GE booths (IDs first; name fallback)
    private List<Map<String, Object>> collectGEBooths()
    {
        List<Map<String, Object>> out = new ArrayList<>();
        Player lp = client.getLocalPlayer();
        if (lp == null) return out;

        WorldPoint me = lp.getWorldLocation();
        int plane = client.getPlane();
        Tile[][] tiles = client.getScene().getTiles()[plane];
        if (tiles == null) return out;

        for (Tile[] row : tiles) {
            if (row == null) continue;
            for (Tile tile : row) {
                if (tile == null) continue;

                GameObject[] gos = tile.getGameObjects();
                if (gos == null) continue;
                for (GameObject go : gos) {
                    if (go == null) continue;

                    int id = go.getId();
                    boolean matchById = (id == GE_BOOTH_ID_A || id == GE_BOOTH_ID_B);
                    boolean matchByName = false;

                    if (!matchById) {
                        try {
                            ObjectComposition def = client.getObjectDefinition(id);
                            String nm = (def != null) ? def.getName() : null;
                            matchByName = (nm != null && nm.equalsIgnoreCase("Grand Exchange booth"));
                        } catch (Exception ignored) {}
                    }

                    if (matchById || matchByName) {
                        out.add(createTileObjectInfo(go, me));
                    }
                }
            }
        }

        // Keep nearest first (helps your consumer pick a sensible one)
        out.sort((a, b) -> Double.compare(
                ((Number)a.getOrDefault("distance", 9e9)).doubleValue(),
                ((Number)b.getOrDefault("distance", 9e9)).doubleValue()
        ));
        return out;
    }

    // (Optional) very lightweight flag; you can replace with exact widget checks later.
    private boolean isGEInterfaceOpen()
    {
        // TODO: Replace with precise widget checks when you wire GE UI (groupId etc.)
        // For now, conservative default:
        return false;
    }

    /**
     * Create Game Object info map
     */
    private Map<String, Object> createGameObjectInfo(GameObject obj, WorldPoint playerPos)
    {
        Map<String, Object> objInfo = new HashMap<>();
        WorldPoint objPos = obj.getWorldLocation();
        
        objInfo.put("id", obj.getId());
        objInfo.put("worldX", objPos.getX());
        objInfo.put("worldY", objPos.getY());
        objInfo.put("plane", objPos.getPlane());
        objInfo.put("distance", playerPos.distanceTo(objPos));
        
        try
        {
            ObjectComposition objDef = client.getObjectDefinition(obj.getId());
            objInfo.put("name", objDef.getName());
            objInfo.put("actions", objDef.getActions());
            
            // Add canvas coordinates
            Point canvasPos = obj.getCanvasLocation();
            objInfo.put("canvasX", canvasPos != null ? canvasPos.getX() : -1);
            objInfo.put("canvasY", canvasPos != null ? canvasPos.getY() : -1);
            
            // Add clickbox information
            try
            {
                Rectangle objectRect = obj.getClickbox().getBounds();
                Map<String, Object> clickbox = new HashMap<>();
                clickbox.put("x", objectRect.x);
                clickbox.put("y", objectRect.y);
                clickbox.put("width", objectRect.width);
                clickbox.put("height", objectRect.height);
                objInfo.put("clickbox", clickbox);
            }
            catch (Exception e)
            {
                objInfo.put("clickbox", null);
            }
        }
        catch (Exception e)
        {
            objInfo.put("name", "Unknown");
            objInfo.put("actions", new String[0]);
            objInfo.put("canvasX", -1);
            objInfo.put("canvasY", -1);
            objInfo.put("clickbox", null);
        }
        
        return objInfo;
    }
    
    /**
     * Parse prioritized names from comma-separated string
     */
    private List<String> getPrioritizedNames(String prioritizedString)
    {
        if (prioritizedString == null || prioritizedString.trim().isEmpty())
        {
            return new ArrayList<>();
        }
        
        return Arrays.stream(prioritizedString.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * Refresh the gamestates directory to the latest recording session
     */
    public void refreshGamestatesDirectory()
    {
        String newDirectory = StateExporter2Config.getDefaultGamestatesDirectory();
        log.info("Refreshing gamestates directory to: {}", newDirectory);
        
        // Update the config with the new directory
        configManager.setConfiguration("stateexporter2", "gamestatesDirectory", newDirectory);
        
        log.info("Gamestates directory updated to: {}", newDirectory);
    }

    // --- Helpers ---
    private Map<String, Object> widgetRect(Widget w)
    {
        if (w == null) return null;
        Rectangle r = w.getBounds();
        if (r == null) return null;
        Map<String, Object> b = new HashMap<>();
        b.put("x", r.x); b.put("y", r.y); b.put("width", r.width); b.put("height", r.height);
        return b;
    }

    private Map<String, Object> widgetFlatJson(Widget w)
    {
        if (w == null) return null;
        Map<String, Object> m = new HashMap<>();
        m.put("id", w.getId());
        try { m.put("text", w.getText()); } catch (Exception ignored) {}
        try { m.put("textStripped", stripTags(w.getText())); } catch (Exception ignored) { m.put("textStripped", null); }
        try { m.put("spriteId", w.getSpriteId()); } catch (Exception ignored) {}
        Map<String, Object> b = widgetRect(w);
        if (b != null) m.put("bounds", b);
        return m;
    }

    private void putWidgetIfPresent(Map<String, Object> out, int id, Client client)
    {
        try {
            Widget w = client.getWidget(id);
            if (w != null && !w.isHidden()) {              // <—
                out.put(String.valueOf(id), widgetFlatJson(w));
            }
        } catch (Exception ignored) {}
    }


    private static Map<String, Object> widgetBoundsJson(Widget w)
    {
        if (w == null)
        {
            return null;
        }

        Map<String, Object> out = new HashMap<>();

        // AWT bounds rectangle (same rectangle you logged)
        Rectangle r = w.getBounds();
        if (r != null)
        {
            Map<String, Object> rect = new HashMap<>();
            rect.put("x", r.x);
            rect.put("y", r.y);
            rect.put("width", r.width);
            rect.put("height", r.height);
            out.put("bounds", rect);
        }
        else
        {
            out.put("bounds", null);
        }

        return out;
    }

    private void scheduleExportTask(int periodMs)
    {
        if (executor == null)
        {
            return;
        }
        if (exportFuture != null)
        {
            exportFuture.cancel(true);
            exportFuture = null;
        }
        if (periodMs <= 0)
        {
            log.warn("exportIntervalMs <= 0 ({}). Skipping export scheduling.", periodMs);
            return;
        }
        // Run export on the RuneLite client thread for safety
        exportFuture = executor.scheduleAtFixedRate(
                () -> clientThread.invoke(this::exportGamestate),
                0L,
                periodMs,
                TimeUnit.MILLISECONDS
        );
    }

    private void ensureRoomForOne(File dir, int maxToKeep)
    {
        if (dir == null || !dir.isDirectory()) return;
        if (maxToKeep <= 0) maxToKeep = 1;

        File[] files = dir.listFiles((d, name) -> name != null && name.endsWith(".json"));
        if (files == null) return;

        // Oldest → newest
        Arrays.sort(files, java.util.Comparator.comparingLong(File::lastModified));

        // Leave space for exactly one new file: after deletions we want <= maxToKeep - 1 existing files
        int toDelete = files.length - (maxToKeep - 1);
        if (toDelete <= 0) return;

        for (int i = 0; i < toDelete; i++)
        {
            try
            {
                if (!files[i].delete())
                {
                    log.debug("Could not delete old gamestate file: {}", files[i].getAbsolutePath());
                }
            }
            catch (SecurityException se)
            {
                log.warn("Delete blocked for file: {}", files[i].getAbsolutePath(), se);
            }
        }
    }

    private static String stripTags(String s) {
        return (s == null) ? null : Text.removeTags(s).trim();
    }

    /** GE inv item widget → flat json (id, text/name raw+stripped, itemId/qty, bounds) */
    private Map<String, Object> geInvChildJson(Widget w) {
        if (w == null) return null;
        Map<String, Object> m = new HashMap<>();
        m.put("id", w.getId());
        try { m.put("text", w.getText()); } catch (Exception ignored) {}
        try { m.put("name", w.getName()); } catch (Exception ignored) {}
        try { m.put("textStripped", stripTags(w.getText())); } catch (Exception ignored) { m.put("textStripped", null); }
        try { m.put("nameStripped", stripTags(w.getName())); } catch (Exception ignored) { m.put("nameStripped", null); }
        try { m.put("itemId", w.getItemId()); } catch (Exception ignored) { m.put("itemId", -1); }
        try { m.put("itemQuantity", w.getItemQuantity()); } catch (Exception ignored) { m.put("itemQuantity", 0); }
        Map<String, Object> b = widgetRect(w); // your existing bounds helper -> {"x","y","width","height"}
        if (b != null) m.put("bounds", b);
        return m;
    }


    private List<Map<String, Object>> collectTiles15x15()
    {
        List<Map<String, Object>> out = new ArrayList<>();

        Player lp = client.getLocalPlayer();
        if (lp == null) {
            return out;
        }

        WorldPoint here = lp.getWorldLocation();
        int plane = client.getPlane();
        int radius = 15;

        for (int dx = -radius; dx <= radius; dx++)
        {
            for (int dy = -radius; dy <= radius; dy++)
            {
                WorldPoint wp = new WorldPoint(here.getX() + dx, here.getY() + dy, plane);
                LocalPoint lpnt = LocalPoint.fromWorld(client, wp);
                if (lpnt == null) {
                    continue; // not in scene
                }

                Point canvas = Perspective.localToCanvas(client, lpnt, plane);

                Map<String, Object> tile = new HashMap<>();
                tile.put("worldX", wp.getX());
                tile.put("worldY", wp.getY());
                tile.put("plane", plane);
                tile.put("canvasX", (canvas != null) ? canvas.getX() : -1);
                tile.put("canvasY", (canvas != null) ? canvas.getY() : -1);

                out.add(tile);
            }
        }
        return out;
    }

    // Put this INSIDE the class (e.g., near other helpers)
    private List<WorldPoint> demoPathTo(int goalX, int goalY)
    {
        // TODO: implement real A* later; placeholder returns just the goal
        Player lp = client.getLocalPlayer();
        int plane = (lp != null) ? lp.getWorldLocation().getPlane() : 0;
        List<WorldPoint> out = new ArrayList<>();
        out.add(new WorldPoint(goalX, goalY, plane));
        return out;
    }



}
