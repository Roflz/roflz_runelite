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
import net.runelite.api.Quest;
import net.runelite.api.QuestState;

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

    private static final int CHATLEFT_NAME_ID     = 15138820; // S 231.4
    private static final int CHATLEFT_CONTINUE_ID = 15138821; // S 231.5
    private static final int CHATLEFT_TEXT_ID     = 15138822; // S 231.6

    private static final int CHATMENU_OPTIONS_ID  = 14352385; // N 219.1

    // Logging system
    @Inject
    private Client client;

    @Inject
    private StateExporter2Config config;

    @Inject
    private ConfigManager configManager;

    @Inject
    private ItemManager itemManager;


    private final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .serializeNulls()
            .create();


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

            // ---- Camera state (for bot camera control) ----
            try {
                Map<String, Object> cam = new HashMap<>();
                cam.put("X",     client.getCameraX());
                cam.put("Y",     client.getCameraY());
                cam.put("Z",     client.getCameraZ());
                cam.put("Pitch", client.getCameraPitch());
                cam.put("Yaw",   client.getCameraYaw());
                cam.put("Scale", client.getScale());
                cam.put("Zoom",  client.getScale()); // alias for convenience

                data.put("camera", cam);
            } catch (Exception ignored) {
                // keep payload shape stable even if something goes wrong
                Map<String, Object> cam = new HashMap<>();
                cam.put("X", null); cam.put("Y", null); cam.put("Z", null);
                cam.put("Pitch", null); cam.put("Yaw", null);
                cam.put("Scale", null); cam.put("Zoom", null);
                data.put("camera", cam);
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

            ObjBag bag = collectNearbyObjectsOnce(bagRadius());

            data.put("closestGameObjects", buildClosestObjects(bag));
            data.put("ge_booths",          buildGEBoothsFromBag(bag));

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

            // ---- ChatLeft & ChatMenu (visible-only) ----
            try {
                // ChatLeft trio: NAME (text), CONTINUE (exists), TEXT (text)
                Map<String, Object> chatLeft = new HashMap<>();

                Widget wChatName = client.getWidget(15138820);   // ChatLeft.NAME
                boolean nameExists = (wChatName != null && !wChatName.isHidden());
                Map<String, Object> jlName = new HashMap<>();
                jlName.put("exists", nameExists);
                if (nameExists) jlName.put("text", safeText(wChatName));
                chatLeft.put("name", jlName);

                Widget wChatContinue = client.getWidget(15138821); // ChatLeft.CONTINUE
                Map<String, Object> jlContinue = new HashMap<>();
                jlContinue.put("exists", (wChatContinue != null && !wChatContinue.isHidden()));
                chatLeft.put("continue", jlContinue);

                Widget wChatText = client.getWidget(15138822);    // ChatLeft.TEXT
                boolean textExists = (wChatText != null && !wChatText.isHidden());
                Map<String, Object> jlText = new HashMap<>();
                jlText.put("exists", textExists);
                if (textExists) jlText.put("text", safeText(wChatText));
                chatLeft.put("text", jlText);

                data.put("chatLeft", chatLeft);

                // Chatmenu.OPTIONS parent + visible child option texts (any count)
                Map<String, Object> chatMenu = new HashMap<>();
                Map<String, Object> options = new HashMap<>();

                Widget wOptions = client.getWidget(14352385);     // Chatmenu.OPTIONS
                boolean optionsExist = (wOptions != null && !wOptions.isHidden());
                options.put("exists", optionsExist);

                if (optionsExist) {
                    List<String> texts = new ArrayList<>();
                    Widget[] kids = wOptions.getChildren();
                    if (kids != null) {
                        for (Widget c : kids) {
                            if (c == null || c.isHidden()) continue;
                            String t = safeText(c);
                            if (t != null) {
                                String s = t.trim();
                                if (!s.isEmpty()) {
                                    texts.add(s);
                                }
                            }
                        }
                    }
                    options.put("texts", texts);
                }

                chatMenu.put("options", options);
                data.put("chatMenu", chatMenu);
            } catch (Exception ignored) {
                // keep payload shape stable on any failure
                Map<String, Object> chatLeft = new HashMap<>();
                chatLeft.put("name",     new HashMap<String, Object>() {{ put("exists", false); }});
                chatLeft.put("continue", new HashMap<String, Object>() {{ put("exists", false); }});
                chatLeft.put("text",     new HashMap<String, Object>() {{ put("exists", false); }});
                data.put("chatLeft", chatLeft);

                Map<String, Object> chatMenu = new HashMap<>();
                chatMenu.put("options", new HashMap<String, Object>() {{
                    put("exists", false);
                    put("texts", new ArrayList<String>());
                }});
                data.put("chatMenu", chatMenu);
            }

            // ChatRight (player dialogue) — visible-only, mirrors ChatLeft shape
            Map<String, Object> chatRight = new HashMap<>();

            Widget wPlayerName = client.getWidget(14221316);
            boolean prNameExists = (wPlayerName != null && !wPlayerName.isHidden());
            Map<String, Object> prName = new HashMap<>();
            prName.put("exists", prNameExists);
            if (prNameExists) prName.put("text", safeText(wPlayerName));
            chatRight.put("name", prName);

            Widget wPlayerContinue = client.getWidget(14221317);
            Map<String, Object> prContinue = new HashMap<>();
            prContinue.put("exists", (wPlayerContinue != null && !wPlayerContinue.isHidden()));
            chatRight.put("continue", prContinue);

            Widget wPlayerText = client.getWidget(14221318);
            boolean prTextExists = (wPlayerText != null && !wPlayerText.isHidden());
            Map<String, Object> prText = new HashMap<>();
            prText.put("exists", prTextExists);
            if (prTextExists) prText.put("text", safeText(wPlayerText));
            chatRight.put("text", prText);

            data.put("chatRight", chatRight);

            // ---- Quests: state for every quest ----
            try {
                Map<String, Object> quests = new HashMap<>();
                for (Quest q : Quest.values()) {
                    QuestState st = q.getState(client);
                    quests.put(q.getName(), (st != null ? st.name() : null)); // e.g., NOT_STARTED / IN_PROGRESS / FINISHED
                }
                data.put("quests", quests);
            } catch (Exception ignored) {
                data.put("quests", new HashMap<String, Object>());
            }


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
                Map<String, Object> bankWidgets = new HashMap<>();

                // Helpers (local only)
                java.util.function.Function<Widget, Map<String, Object>> flatBounds = (w) -> {
                    Map<String, Object> bj = widgetBoundsJson(w);
                    if (bj == null) return null;
                    Object inner = bj.get("bounds");
                    // If widgetBoundsJson returned {"bounds": {...}}, unwrap it; otherwise keep as-is
                    if (inner instanceof Map) return (Map<String, Object>) inner;
                    return bj; // already a flat bounds map
                };
                java.util.function.Function<Widget, Boolean> isSelected = (w) -> {
                    try {
                        if (w == null) return false;
                        // Unified rule everywhere: null onOpListener => selected
                        return w.getOnOpListener() == null;
                    } catch (Exception ignored) { return false; }
                };
                java.util.function.BiFunction<Integer, String, Map<String, Object>> packToggle = (id, key) -> {
                    Widget w = client.getWidget(id);
                    Map<String, Object> m = new HashMap<>();
                    m.put("bounds", flatBounds.apply(w));       // <- FLAT bounds
                    m.put("selected", isSelected.apply(w));     // <- unified selection logic
                    bankWidgets.put(key, m);
                    return m;
                };

                // ---- Withdraw ITEM (786456) & NOTE (786458) toggles ----
                packToggle.apply(786456, "withdraw_item_toggle");
                packToggle.apply(786458, "withdraw_note_toggle");

                // ---- Quantity quick buttons: 1/5/10/X/All ----
                packToggle.apply(786462, "withdraw_quantity_1");   // 1
                packToggle.apply(786464, "withdraw_quantity_5");   // 5
                packToggle.apply(786466, "withdraw_quantity_10");  // 10
                packToggle.apply(786468, "withdraw_quantity_X");   // X
                packToggle.apply(786470, "withdraw_quantity_all"); // All

                // ---- Deposit inventory button (786476) ----
                Widget wDepositInv = client.getWidget(786476);
                bankWidgets.put("deposit_inventory", widgetBoundsJson(wDepositInv));


                // ---- Bank quantity layer (786460) with detailed options (flattened bounds) ----
                Widget qtyLayer = client.getWidget(786460);
                Map<String, Object> qtyJson = new HashMap<>();
                qtyJson.put("parentId", 786460);

                List<Map<String, Object>> options = new ArrayList<>();

                java.util.function.Predicate<Map<String, Object>> hasPositiveBounds = (bj) -> {
                    if (bj == null) return false;
                    Number w = (Number) bj.get("width");
                    Number h = (Number) bj.get("height");
                    return w != null && h != null && w.intValue() > 0 && h.intValue() > 0;
                };

                if (qtyLayer != null && !qtyLayer.isHidden() && qtyLayer.getChildren() != null) {
                    Widget[] kids = qtyLayer.getChildren();

                    // Children typically in PAIRS (label + indicator). First layer only.
                    for (int i = 0; i + 1 < kids.length; i += 2) {
                        Widget a = kids[i];
                        Widget b = kids[i + 1];
                        if (a == null || b == null) continue;
                        if (a.isHidden() || b.isHidden()) continue;

                        Map<String, Object> aB = flatBounds.apply(a);
                        Map<String, Object> bB = flatBounds.apply(b);
                        boolean aOk = hasPositiveBounds.test(aB);
                        boolean bOk = hasPositiveBounds.test(bB);
                        if (!aOk && !bOk) continue;

                        String aTxt = safeText(a);
                        String bTxt = safeText(b);

                        Widget labelW, indicatorW;
                        Map<String, Object> labelBounds;

                        if (aTxt != null && !aTxt.isEmpty()) {
                            labelW = a; indicatorW = b; labelBounds = aB;
                        } else if (bTxt != null && !bTxt.isEmpty()) {
                            labelW = b; indicatorW = a; labelBounds = bB;
                        } else {
                            if (aOk) { labelW = a; indicatorW = b; labelBounds = aB; }
                            else     { labelW = b; indicatorW = a; labelBounds = bB; }
                        }

                        boolean selected = isSelected.apply(indicatorW);

                        List<Object> onOpDump = null;
                        try {
                            Object[] onOp = indicatorW.getOnOpListener();
                            if (onOp != null) {
                                onOpDump = new ArrayList<>(onOp.length);
                                for (Object o : onOp) onOpDump.add(o);
                            }
                        } catch (Exception ignored) {}

                        Map<String, Object> opt = new HashMap<>();
                        opt.put("text", safeText(labelW));     // "1", "5", "10", "X", "All"
                        opt.put("selected", selected);
                        opt.put("bounds", labelBounds);        // <- FLAT bounds for the clickable label
                        opt.put("textChildId", labelW.getId());
                        opt.put("indicatorChildId", indicatorW.getId());
                        opt.put("indicatorOnOpListener", onOpDump);

                        options.add(opt);
                    }
                }

                qtyJson.put("options", options);
                bankWidgets.put("withdraw_quantity_layer", qtyJson);

                // Commit bundle
                data.put("bank_widgets", bankWidgets);

            } catch (Exception e) {
                // Stable fallback (all flat/nullable)
                Map<String, Object> bankWidgets = new HashMap<>();
                java.util.function.Supplier<Map<String, Object>> emptyToggle = () -> new HashMap<String, Object>() {{
                    put("bounds", null);
                    put("selected", false);
                }};
                bankWidgets.put("withdraw_item_toggle", emptyToggle.get());
                bankWidgets.put("withdraw_note_toggle", emptyToggle.get());
                bankWidgets.put("withdraw_quantity_1", emptyToggle.get());
                bankWidgets.put("withdraw_quantity_5", emptyToggle.get());
                bankWidgets.put("withdraw_quantity_10", emptyToggle.get());
                bankWidgets.put("withdraw_quantity_X", emptyToggle.get());
                bankWidgets.put("withdraw_quantity_all", emptyToggle.get());
                bankWidgets.put("deposit_inventory", new HashMap<String, Object>() {{
                    put("bounds", null);
                }});
                bankWidgets.put("withdraw_quantity_layer", new HashMap<String, Object>() {{
                    put("parentId", 786460);
                    put("options", new ArrayList<Map<String, Object>>());
                }});
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

            // inside extractGameData(), near other top-level fields:
            try {
                Map<String, Object> skills = new HashMap<>();
                skills.put("craftingLevel", client.getRealSkillLevel(Skill.CRAFTING));
                skills.put("craftingXP",    client.getSkillExperience(Skill.CRAFTING));
                data.put("skills", skills);
            } catch (Exception ignored) {
                // keep payload shape stable
                Map<String, Object> skills = new HashMap<>();
                skills.put("craftingLevel", null);
                skills.put("craftingXP", null);
                data.put("skills", skills);
            }

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
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            Player me = client.getLocalPlayer();
            if (me == null) return out;

            WorldPoint meW = me.getWorldLocation();
            int plane = client.getPlane();
            Scene scene = client.getScene();
            if (scene == null) return out;

            Tile[][] tilesPlane = scene.getTiles()[plane];
            if (tilesPlane == null) return out;

            // radius & cap (make sure StateExporter2Config has objectScanRadius())
            final int R   = Math.max(5, Math.min(30, config.objectScanRadius()));
            final int cap = Math.max(1, config.maxGameObjects());

            // prioritized names (case-sensitive match to be consistent with your other filters;
            // switch to equalsIgnoreCase if you prefer)
            final List<String> prioritized = getPrioritizedNames(config.prioritizedGameObjects());
            final boolean hasPrio = !prioritized.isEmpty();

            // center of scan
            LocalPoint meL = me.getLocalLocation();
            int cx = (meL != null) ? meL.getSceneX() : (meW.getX() - client.getBaseX());
            int cy = (meL != null) ? meL.getSceneY() : (meW.getY() - client.getBaseY());

            // collect candidates
            List<TileObject> prioBag   = new ArrayList<>();
            List<TileObject> otherBag  = new ArrayList<>();

            for (int lx = Math.max(0, cx - R); lx <= Math.min(103, cx + R); lx++) {
                Tile[] col = tilesPlane[lx];
                if (col == null) continue;

                for (int ly = Math.max(0, cy - R); ly <= Math.min(103, cy + R); ly++) {
                    Tile t = col[ly];
                    if (t == null) continue;

                    // add all kinds; skip java nulls immediately
                    TileObject[] objs = gatherTileObjects(t);
                    if (objs == null) continue;

                    for (TileObject to : objs) {
                        if (to == null) continue;

                        // Definition/name lookup — filter out “null”/empty names
                        String nm = null;
                        try {
                            ObjectComposition def = client.getObjectDefinition(to.getId());
                            nm = (def != null) ? def.getName() : null;
                        } catch (Exception ignored) {}

                        if (nm == null || nm.isEmpty() || "null".equalsIgnoreCase(nm)) {
                            // filtered out per your requirement
                            continue;
                        }

                        // Partition by priority (don’t cap yet)
                        if (hasPrio && prioritized.contains(nm)) {
                            prioBag.add(to);
                        } else {
                            otherBag.add(to);
                        }
                    }
                }
            }

            // sort each partition by distance (nearest first)
            java.util.Comparator<TileObject> byDist = (a, b) ->
                    Double.compare(meW.distanceTo(a.getWorldLocation()), meW.distanceTo(b.getWorldLocation()));
            prioBag.sort(byDist);
            otherBag.sort(byDist);

            // fill output: take from prioritized first, then others, up to cap
            for (TileObject to : prioBag) {
                if (out.size() >= cap) break;
                out.add(createTileObjectInfo(to, meW));
            }
            for (TileObject to : otherBag) {
                if (out.size() >= cap) break;
                out.add(createTileObjectInfo(to, meW));
            }
        } catch (Exception e) {
            log.error("Error getting closest game objects: {}", e.getMessage());
        }
        return out;
    }

    /** Gather all tile object kinds into a simple array; null-safe. */
    private static TileObject[] gatherTileObjects(Tile t) {
        if (t == null) return null;
        List<TileObject> all = new ArrayList<>(8);
        WallObject w = t.getWallObject();
        if (w != null) all.add(w);
        DecorativeObject d = t.getDecorativeObject();
        if (d != null) all.add(d);
        GroundObject g = t.getGroundObject();
        if (g != null) all.add(g);
        GameObject[] gos = t.getGameObjects();
        if (gos != null) {
            for (GameObject go : gos) if (go != null) all.add(go);
        }
        return all.toArray(new TileObject[0]);
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

    // NEW: snapshot collector (runs on client thread only)
    private Map<String, Object> captureSnapshotSafe() {
        try {
            // ONLY the data read from the game must happen on client thread:
            return extractGameData(); // unchanged logic
        } catch (Exception e) {
            HashMap<String,Object> m = new HashMap<>();
            m.put("error", "snapshot-failed: " + e.getMessage());
            return m;
        }
    }

    private void scheduleExportTask(int periodMs)
    {
        if (executor == null) return;
        if (exportFuture != null) { exportFuture.cancel(true); exportFuture = null; }
        if (periodMs <= 0) {
            log.warn("exportIntervalMs <= 0 ({}). Skipping export scheduling.", periodMs);
            return;
        }

        exportFuture = executor.scheduleAtFixedRate(() -> {
            try {
                // 1) SNAPSHOT on client thread (fast)
                final java.util.concurrent.CompletableFuture<Map<String,Object>> snap = new java.util.concurrent.CompletableFuture<>();
                clientThread.invoke(() -> {
                    try { snap.complete(captureSnapshotSafe()); }
                    catch (Throwable t) { snap.completeExceptionally(t); }
                });
                Map<String,Object> gameData = snap.get(1000, java.util.concurrent.TimeUnit.MILLISECONDS);

                // 2) JSON + FILE I/O off-thread (we are already off client thread here)
                Map<String,Object> gamestate = new HashMap<>();
                gamestate.put("timestamp", System.currentTimeMillis());
                gamestate.put("plugin", "StateExporter2");
                gamestate.put("version", "1.0.0");
                gamestate.put("data", gameData);

                writeGamestateFile(gamestate); // new tiny helper below
            } catch (Exception e) {
                log.warn("export task failed: {}", e.toString());
            }
        }, 0L, periodMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    // NEW: tiny helper that only does JSON + file I/O off-thread
    private void writeGamestateFile(Map<String,Object> gamestate) throws IOException {
        // (1) directory
        String gamestatesDir = config.gamestatesDirectory();
        File dir = new File(gamestatesDir);
        if (!dir.exists() && !dir.mkdirs()) {
            log.error("Failed to create gamestates directory: {}", gamestatesDir);
            return;
        }
        ensureRoomForOne(dir, config.maxGamestateFiles());

        // (2) filename
        File file = new File(dir, System.currentTimeMillis() + ".json");

        // (3) write (no pretty printing; faster & smaller)
        try (java.io.BufferedWriter w = new java.io.BufferedWriter(new java.io.FileWriter(file))) {
            w.write(gson.toJson(gamestate));
            w.write("\n");
        }

        pruneGamestateFiles(dir, config.maxGamestateFiles());
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

    // Treat a widget as visible only if it and its parents aren't hidden and it has >0x>0 bounds.
    private boolean isEffectivelyVisible(Widget w)
    {
        if (w == null) return false;
        try {
            Widget cur = w;
            while (cur != null) {
                if (cur.isHidden()) return false;
                cur = cur.getParent();
            }
        } catch (Exception ignored) {}
        // Also require positive bounds (same idea you use for GE children)
        try {
            Rectangle r = w.getBounds();
            if (r == null || r.width <= 0 || r.height <= 0) return false;
        } catch (Exception ignored) {
            return false;
        }
        return true;
    }

    private void putChatWidgetIfVisible(Map<String, Object> out, int id)
    {
        try {
            Widget w = client.getWidget(id);
            if (w == null || !isEffectivelyVisible(w)) return;

            Map<String, Object> jw = widgetFlatJson(w); // your existing flattener
            if (jw == null) return;

            @SuppressWarnings("unchecked")
            Map<String, Object> b = (Map<String, Object>) jw.get("bounds");
            if (b == null) return;
            if (((Number)b.get("width")).intValue() <= 0 || ((Number)b.get("height")).intValue() <= 0) return;

            // Ensure textStripped is present (parity with other exports)
            Object t = jw.get("text");
            if (t instanceof String && !jw.containsKey("textStripped")) {
                jw.put("textStripped", ((String)t).replaceAll("<[^>]*>", ""));
            }

            out.put(String.valueOf(id), jw);
        } catch (Exception ignored) {}
    }

    // Returns Widget.getText() safely; never throws; never null
    private static String safeText(Widget w) {
        if (w == null) return "";
        try {
            String t = w.getText();
            return (t != null) ? t : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private int bagRadius() {
        // single knob; already requested earlier
        return Math.max(5, Math.min(30, config.objectScanRadius()));
    }

    private ObjBag collectNearbyObjectsOnce(int R) {
        ObjBag bag = new ObjBag();
        Player me = client.getLocalPlayer();
        if (me == null) return bag;
        int plane = client.getPlane();
        Tile[][] tiles = client.getScene().getTiles()[plane];
        if (tiles == null) return bag;

        LocalPoint meL = me.getLocalLocation();
        int cx = meL.getSceneX(), cy = meL.getSceneY();

        for (int lx = Math.max(0, cx - R); lx <= Math.min(103, cx + R); lx++) {
            for (int ly = Math.max(0, cy - R); ly <= Math.min(103, cy + R); ly++) {
                Tile t = tiles[lx][ly];
                if (t == null) continue;
                GameObject[] gos = t.getGameObjects();
                if (gos == null) continue;
                for (GameObject go : gos) {
                    if (go == null) continue;
                    bag.all.add(go);
                }
            }
        }
        return bag;
    }

    private List<Map<String,Object>> buildClosestObjects(ObjBag bag) {
        List<Map<String,Object>> out = new ArrayList<>();
        Player me = client.getLocalPlayer();
        if (me == null) return out;

        WorldPoint meW = me.getWorldLocation();
        List<String> prioritized = getPrioritizedNames(config.prioritizedGameObjects());
        boolean hasPrio = !prioritized.isEmpty();

        // distance sort
        bag.all.sort((a,b) -> Double.compare(
                meW.distanceTo(a.getWorldLocation()),
                meW.distanceTo(b.getWorldLocation())
        ));

        int cap = Math.max(1, config.maxGameObjects());

        // prioritized first
        if (hasPrio) {
            for (GameObject go : bag.all) {
                if (out.size() >= cap) break;
                String nm = defName(bag, go.getId());
                if (nm != null && prioritized.contains(nm)) out.add(createGameObjectInfo(go, meW));
            }
        }
        // then the rest
        for (GameObject go : bag.all) {
            if (out.size() >= cap) break;
            String nm = defName(bag, go.getId());
            if (!hasPrio || nm == null || !prioritized.contains(nm)) {
                out.add(createGameObjectInfo(go, meW));
            }
        }
        return out;
    }

    private List<Map<String,Object>> buildGEBoothsFromBag(ObjBag bagIgnored) {
        List<Map<String,Object>> out = new ArrayList<>();
        Player me = client.getLocalPlayer();
        if (me == null) return out;
        WorldPoint meW = me.getWorldLocation();

        final int R = Math.max(8, Math.min(30, bagRadius())); // small but reliable radius
        List<TileObject> near = scanNearbyTileObjects(R);

        // --- pass 1: prioritized names (mandatory) ---
        List<String> prioritized = getPrioritizedNames(config.prioritizedGameObjects());
        if (!prioritized.isEmpty()) {
            for (TileObject to : near) {
                String nm = null;
                try {
                    ObjectComposition def = client.getObjectDefinition(to.getId());
                    nm = (def != null) ? def.getName() : null;
                } catch (Exception ignored) {}
                if (nm == null || nm.isEmpty() || "null".equalsIgnoreCase(nm)) continue;
                if (prioritized.contains(nm)) {
                    out.add(createTileObjectInfo(to, meW));
                }
            }
        }

        // --- pass 2: GE booth by ID or name (if not already added) ---
        for (TileObject to : near) {
            int id = to.getId();
            boolean idMatch = (id == GE_BOOTH_ID_A || id == GE_BOOTH_ID_B);
            boolean nameMatch = false;
            if (!idMatch) {
                String nm = null;
                try {
                    ObjectComposition def = client.getObjectDefinition(id);
                    nm = (def != null) ? def.getName() : null;
                } catch (Exception ignored) {}
                nameMatch = nm != null && nm.equalsIgnoreCase("Grand Exchange booth");
            }

            if (idMatch || nameMatch) {
                // avoid duplicates if prioritized pass already added it
                // (dedupe by id + world coords)
                Map<String,Object> info = createTileObjectInfo(to, meW);
                boolean dup = false;
                int wx = ((Number)info.get("worldX")).intValue();
                int wy = ((Number)info.get("worldY")).intValue();
                int pid = ((Number)info.get("id")).intValue();
                for (Map<String,Object> m : out) {
                    if (((Number)m.get("id")).intValue() == pid
                            && ((Number)m.get("worldX")).intValue() == wx
                            && ((Number)m.get("worldY")).intValue() == wy) {
                        dup = true; break;
                    }
                }
                if (!dup) out.add(info);
            }
        }

        // nearest-first
        out.sort((a,b) -> Double.compare(
                ((Number)a.getOrDefault("distance", 9e9)).doubleValue(),
                ((Number)b.getOrDefault("distance", 9e9)).doubleValue()
        ));

        return out;
    }


    private String defName(ObjBag bag, int id) {
        try {
            ObjectComposition def = bag.defs.computeIfAbsent(id, k -> client.getObjectDefinition(k));
            if (def == null) return null;
            String nm = def.getName();
            return (nm == null || "null".equals(nm)) ? null : nm;
        } catch (Exception e) { return null; }
    }

    class ObjBag {
        final List<GameObject> all = new ArrayList<>();
        final Map<Integer,ObjectComposition> defs = new HashMap<>();
    }

    private List<TileObject> scanNearbyTileObjects(int R) {
        List<TileObject> out = new ArrayList<>();
        Player me = client.getLocalPlayer();
        if (me == null) return out;
        int plane = client.getPlane();
        Tile[][] tiles = client.getScene().getTiles()[plane];
        if (tiles == null) return out;

        LocalPoint meL = me.getLocalLocation();
        int cx = meL.getSceneX(), cy = meL.getSceneY();

        for (int lx = Math.max(0, cx - R); lx <= Math.min(103, cx + R); lx++) {
            Tile[] col = tiles[lx];
            if (col == null) continue;
            for (int ly = Math.max(0, cy - R); ly <= Math.min(103, cy + R); ly++) {
                Tile t = col[ly];
                if (t == null) continue;

                // collect ALL kinds
                WallObject w = t.getWallObject();
                if (w != null) out.add(w);
                DecorativeObject d = t.getDecorativeObject();
                if (d != null) out.add(d);
                GroundObject g = t.getGroundObject();
                if (g != null) out.add(g);
                GameObject[] gos = t.getGameObjects();
                if (gos != null) for (GameObject go : gos) if (go != null) out.add(go);
            }
        }
        return out;
    }

    private static boolean nameLike(String s, String... subs) {
        if (s == null) return false;
        String n = s.toLowerCase();
        if (n.isEmpty() || "null".equals(n)) return false;
        for (String sub : subs) {
            if (n.contains(sub)) return true;
        }
        return false;
    }

    private static String[] safeActions(ObjectComposition def) {
        try {
            String[] a = def.getActions();
            if (a == null) return new String[0];
            return a;
        } catch (Exception e) {
            return new String[0];
        }
    }

    private Map<String, Object> packWorldObject(GameObject obj) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", obj.getId());
        try {
            ObjectComposition def = client.getObjectDefinition(obj.getId());
            m.put("name", def.getName());
            m.put("actions", safeActions(def));
        } catch (Exception e) {
            m.put("name", "Unknown");
            m.put("actions", new String[0]);
        }
        WorldPoint wp = obj.getWorldLocation();
        m.put("worldX", wp.getX());
        m.put("worldY", wp.getY());
        m.put("plane",  wp.getPlane());
        m.put("canvasX", obj.getCanvasLocation().getX());
        m.put("canvasY", obj.getCanvasLocation().getY());
        // distance if you already store it elsewhere, otherwise compute from local player
        try {
            Player me = client.getLocalPlayer();
            WorldPoint meWp = me.getWorldLocation();
            int dx = Math.abs(meWp.getX() - wp.getX());
            int dy = Math.abs(meWp.getY() - wp.getY());
            m.put("distance", Math.max(dx, dy));
        } catch (Exception ignored) {}
        try {
            Rectangle r = obj.getClickbox() != null ? obj.getClickbox().getBounds() : null;
            if (r != null) {
                Map<String, Object> cb = new HashMap<>();
                cb.put("x", r.x); cb.put("y", r.y);
                cb.put("width", r.width); cb.put("height", r.height);
                m.put("clickbox", cb);
            } else {
                m.put("clickbox", null);
            }
        } catch (Exception e) {
            m.put("clickbox", null);
        }
        return m;
    }

}
