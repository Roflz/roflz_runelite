package net.runelite.client.plugins.stateexporter2;

import com.google.inject.Provides;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.Point;
import net.runelite.api.coords.WorldPoint;
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

    // Logging system
    @Inject
    private Client client;

    @Inject
    private StateExporter2Config config;

    @Inject
    private ConfigManager configManager;

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


            // ---- Crafting widgets: export bounds (or null if missing)
            Widget wCrafting = client.getWidget(WIDGET_CRAFTING_INTERFACE);
            Widget wMakeRing = client.getWidget(WIDGET_MAKE_SAPPHIRE_RINGS);
            Map<String, Object> craftingWidgets = new HashMap<>();
            craftingWidgets.put("crafting_interface", widgetBoundsJson(wCrafting));
            craftingWidgets.put("make_sapphire_rings", widgetBoundsJson(wMakeRing));
            data.put("crafting_widgets", craftingWidgets);

             // Crafting interface status - simplified check
             boolean craftingInterfaceOpen = (wCrafting != null) || (wMakeRing != null);
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
        }
        catch (Exception e)
        {
            npcInfo.put("actions", new String[0]);
        }
        
        return npcInfo;
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


}
