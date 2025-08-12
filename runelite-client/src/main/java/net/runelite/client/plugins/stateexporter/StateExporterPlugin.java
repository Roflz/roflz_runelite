package net.runelite.client.plugins.stateexporter;

import com.google.inject.Provides;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;

import javax.inject.Inject;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.awt.AWTException;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

@Slf4j
@PluginDescriptor(
    name = "State Exporter"
)
public class StateExporterPlugin extends Plugin {
    @Inject
    private Client client;

    @Inject
    private StateExporterConfig config;

    @Inject
    private ItemManager itemManager;

    @Provides
    StateExporterConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(StateExporterConfig.class);
    }

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    // Action history for RL (store last action taken)
    private String lastAction = null;
    private String lastTarget = null;
    private Map<String, Object> lastInteraction = null;
    private Map<String, Object> lastMovement = null;
    private boolean playerMoving = false;
    private int lastPlayerX = -1;
    private int lastPlayerY = -1;

    // Call this method from your bot logic to update last action
    public void setLastAction(String action) {
        this.lastAction = action;
    }

    // Track menu interactions to understand click context
    @Subscribe
    public void onMenuOpened(MenuOpened event) {
        if (event.getMenuEntries().length > 0) {
            MenuEntry entry = event.getMenuEntries()[0];
            if (entry.getTarget() != null) {
                lastTarget = entry.getTarget();
                log.debug("Menu opened with target: {}", lastTarget);
            }
        }
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event) {
        if (event.getMenuOption() != null && event.getMenuTarget() != null) {
            lastAction = event.getMenuOption() + " " + event.getMenuTarget();
            log.debug("Menu clicked: {} on {}", event.getMenuOption(), event.getMenuTarget());
            
            // Enhanced item interaction tracking - capture EVERYTHING
            Map<String, Object> interaction = new HashMap<>();
            interaction.put("action", event.getMenuOption());
            interaction.put("target", event.getMenuTarget());
            interaction.put("timestamp", System.currentTimeMillis());
            
            // Always try to parse item information from target
            if (event.getMenuTarget().contains(">")) {
                String itemName = event.getMenuTarget().split(">")[1].trim();
                interaction.put("item_name", itemName);
                
                // Try to determine context from action and target
                String action = event.getMenuOption().toLowerCase();
                String target = event.getMenuTarget().toLowerCase();
                
                // Bank interactions
                if (action.contains("deposit")) {
                    interaction.put("source", "inventory");
                    interaction.put("destination", "bank");
                } else if (action.contains("withdraw")) {
                    interaction.put("source", "bank");
                    interaction.put("destination", "inventory");
                }
                
                // Movement interactions
                if (action.contains("walk") || action.contains("run")) {
                    interaction.put("type", "movement");
                    // Extract coordinates if available
                    if (target.contains("(") && target.contains(")")) {
                        String coords = target.substring(target.indexOf("(") + 1, target.indexOf(")"));
                        if (coords.contains(",")) {
                            String[] parts = coords.split(",");
                            if (parts.length == 2) {
                                try {
                                    int x = Integer.parseInt(parts[0].trim());
                                    int y = Integer.parseInt(parts[1].trim());
                                    interaction.put("destination_x", x);
                                    interaction.put("destination_y", y);
                                } catch (NumberFormatException e) {
                                    // Ignore parsing errors
                                }
                            }
                        }
                    }
                }
                
                // Object interactions
                if (action.contains("use") || action.contains("examine") || 
                    action.contains("chop") || action.contains("mine") || 
                    action.contains("fish") || action.contains("cook") ||
                    action.contains("craft") || action.contains("smith")) {
                    interaction.put("type", "object_interaction");
                }
                
                // Item interactions
                if (action.contains("use") || action.contains("eat") || 
                    action.contains("drink") || action.contains("wield") || 
                    action.contains("remove") || action.contains("drop")) {
                    interaction.put("type", "item_interaction");
                }
            }
            
            // Enhanced movement tracking
            if (event.getMenuOption().toLowerCase().contains("walk") || 
                event.getMenuOption().toLowerCase().contains("run")) {
                
                // Try to extract destination coordinates
                String target = event.getMenuTarget();
                if (target.contains("(") && target.contains(")")) {
                    String coords = target.substring(target.indexOf("(") + 1, target.indexOf(")"));
                    if (coords.contains(",")) {
                        String[] parts = coords.split(",");
                        if (parts.length == 2) {
                            try {
                                int x = Integer.parseInt(parts[0].trim());
                                int y = Integer.parseInt(parts[1].trim());
                                
                                Map<String, Object> movement = new HashMap<>();
                                movement.put("type", "player_movement");
                                movement.put("destination_x", x);
                                movement.put("destination_y", y);
                                movement.put("action", event.getMenuOption());
                                movement.put("timestamp", System.currentTimeMillis());
                                
                                // Store movement info
                                lastMovement = movement;
                            } catch (NumberFormatException e) {
                                // Ignore parsing errors
                            }
                        }
                    }
                }
            }
            
            // Store the enhanced interaction
            lastInteraction = interaction;
        }
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        Map<String, Object> state = new HashMap<>();
        
        // Timestamp and context
        long timestamp = System.currentTimeMillis();
        String timestampStr = String.valueOf(timestamp);
        state.put("timestamp", timestamp);
        state.put("world", client.getWorld());
        
        // Player info (Enhanced with animation names and movement tracking)
            Player player = client.getLocalPlayer();
            if (player != null) {
                Map<String, Object> playerMap = new HashMap<>();
                playerMap.put("world_x", player.getWorldLocation().getX());
                playerMap.put("world_y", player.getWorldLocation().getY());
                playerMap.put("plane", player.getWorldLocation().getPlane());
                playerMap.put("health", player.getHealthRatio());
            
            // Enhanced animation information with names
            int animationId = player.getAnimation();
            String animationName = getAnimationName(animationId);
            playerMap.put("animation_id", animationId);
            playerMap.put("animation_name", animationName);
            
            // Track player movement
            int currentX = player.getWorldLocation().getX();
            int currentY = player.getWorldLocation().getY();
            playerMoving = (currentX != lastPlayerX || currentY != lastPlayerY);
            playerMap.put("is_moving", playerMoving);
            playerMap.put("movement_direction", getMovementDirection(lastPlayerX, lastPlayerY, currentX, currentY));
            
            // Update last position for next tick
            lastPlayerX = currentX;
            lastPlayerY = currentY;
            
                playerMap.put("run_energy", client.getEnergy());
                playerMap.put("prayer", client.getBoostedSkillLevel(Skill.PRAYER));
                playerMap.put("special_attack", client.getVar(VarPlayer.SPECIAL_ATTACK_PERCENT));
            
            // Add last action and target context
            playerMap.put("last_action", lastAction);
            playerMap.put("last_target", lastTarget);
            
            // Add enhanced interaction if available
            if (lastInteraction != null) {
                playerMap.put("last_interaction", lastInteraction);
            }
            
            // Add movement tracking if available
            if (lastMovement != null) {
                playerMap.put("last_movement", lastMovement);
            }
            
            state.put("player", playerMap);
        }
        
        // Camera info (5 features) - Essential for movement and interaction
        int cameraX = client.getCameraX();
        int cameraY = client.getCameraY();
        int cameraZ = client.getCameraZ();
        int cameraPitch = client.getCameraPitch();
        int cameraYaw = client.getCameraYaw();
        
        // Debug logging for camera values
        if (cameraX == 0 && cameraY == 0 && cameraZ == 0) {
            log.warn("Camera coordinates are all 0 - client may not be fully loaded");
        }
        
        state.put("camera_x", cameraX);
        state.put("camera_y", cameraY);
        state.put("camera_z", cameraZ);
        state.put("camera_pitch", cameraPitch);
        state.put("camera_yaw", cameraYaw);
        
        // Inventory (Enhanced with item names) - 28 slots × 2 (id, quantity) + names
            ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
            if (inv != null) {
                List<Map<String, Object>> inventory = new ArrayList<>();
                for (Item item : inv.getItems()) {
                    Map<String, Object> itemMap = new HashMap<>();
                    itemMap.put("id", item.getId());
                    itemMap.put("quantity", item.getQuantity());
                
                // Get item name from RuneLite client
                ItemComposition itemComp = itemManager.getItemComposition(item.getId());
                if (itemComp != null) {
                    itemMap.put("name", itemComp.getName());
                    itemMap.put("members", itemComp.isMembers());
                } else {
                    itemMap.put("name", "Unknown Item");
                    itemMap.put("members", false);
                }
                
                inventory.add(itemMap);
                }
                state.put("inventory", inventory);
        }
        
        // Bank state (Enhanced with quantity modes and item names)
        boolean bankOpen = false;
        Widget bankWidget = client.getWidget(WidgetInfo.BANK_CONTAINER);
        if (bankWidget != null && !bankWidget.isHidden()) {
            bankOpen = true;
        }
        state.put("bank_open", bankOpen);
        
        // Bank quantity button status (Enhanced)
        if (bankOpen) {
            int quantityType = client.getVarbitValue(VarbitID.BANK_QUANTITY_TYPE);
            int requestQty = client.getVarbitValue(VarbitID.BANK_REQUESTEDQUANTITY);
            
            // Debug logging for bank quantity values
            log.info("Bank open - Quantity type: {}, Requested quantity: {}", quantityType, requestQty);
            
            String quantityMode;
            switch (quantityType) {
                case 0:
                    quantityMode = "1";
                    break;
                case 1:
                    quantityMode = "5";
                    break;
                case 2:
                    quantityMode = "10";
                    break;
                case 3:
                    quantityMode = "X";
                    break;
                case 4:
                    quantityMode = "All";
                    break;
                default:
                    quantityMode = "1";
                    break;
            }
            
            Map<String, Object> bankQuantityInfo = new HashMap<>();
            bankQuantityInfo.put("mode", quantityMode);
            bankQuantityInfo.put("custom_quantity", requestQty);
            state.put("bank_quantity", bankQuantityInfo);
        } else {
            log.debug("Bank is closed");
            state.put("bank_quantity", null);
        }
        
        // Bank contents (Enhanced with item names)
        List<Map<String, Object>> bankItems = new ArrayList<>();
        if (bankOpen) {
            ItemContainer bankContainer = client.getItemContainer(InventoryID.BANK);
            if (bankContainer != null) {
                for (Item item : bankContainer.getItems()) {
                Map<String, Object> itemMap = new HashMap<>();
                itemMap.put("id", item.getId());
                itemMap.put("quantity", item.getQuantity());
                    
                    // Get item name from RuneLite client
                    ItemComposition itemComp = itemManager.getItemComposition(item.getId());
                    if (itemComp != null) {
                        itemMap.put("name", itemComp.getName());
                        itemMap.put("members", itemComp.isMembers());
                    } else {
                        itemMap.put("name", "Unknown Item");
                        itemMap.put("members", false);
                    }
                    
                    bankItems.add(itemMap);
                }
            }
        }
        state.put("bank", bankItems);
        
        // Game Objects (Enhanced with names and bank booth/furnace detection)
            List<Map<String, Object>> objects = new ArrayList<>();
        List<Map<String, Object>> bankBooths = new ArrayList<>();
        List<Map<String, Object>> furnaces = new ArrayList<>();
            Scene scene = client.getScene();
            Tile[][][] tiles = scene.getTiles();
        int px = player != null ? player.getWorldLocation().getX() : 0;
        int py = player != null ? player.getWorldLocation().getY() : 0;
        
            for (int plane = 0; plane < tiles.length; plane++) {
                for (int x = 0; x < tiles[plane].length; x++) {
                    for (int y = 0; y < tiles[plane][x].length; y++) {
                        Tile tile = tiles[plane][x][y];
                        if (tile == null) continue;
                    
                        for (GameObject obj : tile.getGameObjects()) {
                            if (obj != null) {
                                Map<String, Object> objMap = new HashMap<>();
                                objMap.put("id", obj.getId());
                            
                            // Get object name and definition from RuneLite client
                                ObjectComposition objDef = client.getObjectDefinition(obj.getId());
                            String objName = objDef != null ? objDef.getName() : "Unknown Object";
                                objMap.put("name", objName);
                                objMap.put("x", obj.getWorldLocation().getX());
                                objMap.put("y", obj.getWorldLocation().getY());
                                objMap.put("plane", obj.getWorldLocation().getPlane());
                            
                            // Calculate distance for prioritization
                            int objX = obj.getWorldLocation().getX();
                            int objY = obj.getWorldLocation().getY();
                            double distance = Math.sqrt((objX - px) * (objX - px) + (objY - py) * (objY - py));
                            objMap.put("distance", distance);
                            
                            // Check if this is a bank booth
                            if (objName != null && objName.toLowerCase().contains("bank")) {
                                Map<String, Object> boothMap = new HashMap<>(objMap);
                                boothMap.put("type", "bank_booth");
                                bankBooths.add(boothMap);
                            }
                            
                            // Check if this is a furnace
                            if (objName != null && objName.toLowerCase().contains("furnace")) {
                                Map<String, Object> furnaceMap = new HashMap<>(objMap);
                                furnaceMap.put("type", "furnace");
                                furnaces.add(furnaceMap);
                            }
                            
                            objects.add(objMap);
                        }
                    }
                }
            }
        }
        
        // Sort bank booths by distance and keep closest ones
        bankBooths.sort((a, b) -> Double.compare((Double) a.get("distance"), (Double) b.get("distance")));
        if (bankBooths.size() > 5) {
            bankBooths = bankBooths.subList(0, 5);
        }
        
        // Sort furnaces by distance and keep closest ones
        furnaces.sort((a, b) -> Double.compare((Double) a.get("distance"), (Double) b.get("distance")));
        if (furnaces.size() > 3) {
            furnaces = furnaces.subList(0, 3);
        }
        
        log.debug("Collected {} game objects, {} bank booths, {} furnaces", objects.size(), bankBooths.size(), furnaces.size());
        state.put("game_objects", objects);
        state.put("bank_booths", bankBooths);
        state.put("furnaces", furnaces);
        
        // NPCs - Only bankers (closest 5) - Enhanced with names
        List<Map<String, Object>> npcs = new ArrayList<>();
        for (NPC npc : client.getNpcs()) {
            if (npc.getWorldLocation() != null) {
                String npcName = npc.getName();
                if (npcName != null && npcName.toLowerCase().contains("bank")) {
                    Map<String, Object> npcMap = new HashMap<>();
                    npcMap.put("id", npc.getId());
                    npcMap.put("name", npcName);
                    npcMap.put("x", npc.getWorldLocation().getX());
                    npcMap.put("y", npc.getWorldLocation().getY());
                    npcMap.put("plane", npc.getWorldLocation().getPlane());
                    npcMap.put("health", npc.getHealthRatio());
                    
                    // Calculate distance to player
                    int npcX = npc.getWorldLocation().getX();
                    int npcY = npc.getWorldLocation().getY();
                    double distance = Math.sqrt((npcX - px) * (npcX - px) + (npcY - py) * (npcY - py));
                    npcMap.put("distance", distance);
                    
                    npcs.add(npcMap);
                }
            }
        }
        
        // Sort NPCs by distance and keep closest 5
        npcs.sort((a, b) -> Double.compare((Double) a.get("distance"), (Double) b.get("distance")));
        if (npcs.size() > 5) {
            npcs = npcs.subList(0, 5);
        }
        
        state.put("npcs", npcs);
        
        // Minimap info (4 features) - Essential for navigation
            Map<String, Object> minimapInfo = new HashMap<>();
            minimapInfo.put("base_x", client.getBaseX());
            minimapInfo.put("base_y", client.getBaseY());
            minimapInfo.put("map_regions", Arrays.toString(client.getMapRegions()));
        minimapInfo.put("local_player_region", player != null ? player.getWorldLocation().getRegionID() : -1);
            state.put("minimap_world_info", minimapInfo);
        
        // Skills - Only crafting (2 features) - level and xp
        Map<String, Object> skills = new HashMap<>();
        Map<String, Object> craftingData = new HashMap<>();
        craftingData.put("level", client.getRealSkillLevel(Skill.CRAFTING));
        craftingData.put("xp", client.getSkillExperience(Skill.CRAFTING));
        skills.put("crafting", craftingData);
        state.put("skills", skills);
        
        // Chatbox - Focus on game messages and level ups
            List<Map<String, Object>> chatMessages = new ArrayList<>();
            
            // Collect game messages and level up messages
            for (MessageNode msg : client.getMessages()) {
                ChatMessageType msgType = msg.getType();
                
                // Include game messages and level up messages
                boolean isGameMessage = msgType == ChatMessageType.GAMEMESSAGE;
                boolean isEngineMessage = msgType == ChatMessageType.ENGINE;
                boolean isLevelUp = isGameMessage && msg.getValue() != null && 
                                   msg.getValue().contains("Congratulations! You've just advanced");
                
                if (isGameMessage || isEngineMessage || isLevelUp) {
                    
                    Map<String, Object> msgMap = new HashMap<>();
                    msgMap.put("type", msg.getType().toString());
                    msgMap.put("sender", msg.getName() != null ? msg.getName() : "");
                    msgMap.put("text", msg.getValue() != null ? msg.getValue() : "");
                    msgMap.put("timestamp", msg.getTimestamp());
                    chatMessages.add(msgMap);
                }
            }
            
            // Keep only the most recent messages
            if (chatMessages.size() > 5) {
                chatMessages = chatMessages.subList(chatMessages.size() - 5, chatMessages.size());
            }
            
            state.put("chatbox", chatMessages);
        
        // Tabs (13 features) - All tabs active/inactive
            Map<String, Object> tabs = new HashMap<>();
            tabs.put("currentTab", client.getVar(net.runelite.api.VarClientInt.INVENTORY_TAB));
        
            // Inventory tab details
            ItemContainer invContainer = client.getItemContainer(InventoryID.INVENTORY);
            if (invContainer != null) {
                List<Map<String, Object>> invItems = new ArrayList<>();
                for (Item item : invContainer.getItems()) {
                    Map<String, Object> itemMap = new HashMap<>();
                    itemMap.put("id", item.getId());
                    itemMap.put("quantity", item.getQuantity());
                    invItems.add(itemMap);
                }
                tabs.put("inventory", invItems);
            }
        
            // Equipment tab details
            ItemContainer equipContainer = client.getItemContainer(InventoryID.EQUIPMENT);
            if (equipContainer != null) {
                List<Map<String, Object>> equipItems = new ArrayList<>();
                for (Item item : equipContainer.getItems()) {
                    Map<String, Object> itemMap = new HashMap<>();
                    itemMap.put("id", item.getId());
                    itemMap.put("quantity", item.getQuantity());
                    equipItems.add(itemMap);
                }
                tabs.put("equipment", equipItems);
            }
        
            // Combat tab details (group 593)
            Widget combatWidget = client.getWidget(593, 0);
            if (combatWidget != null) {
                List<String> combatStyles = new ArrayList<>();
                Widget[] children = combatWidget.getChildren();
                if (children != null) {
                    for (Widget child : children) {
                        if (child != null && child.getText() != null && !child.getText().isEmpty()) {
                            combatStyles.add(child.getText());
                        }
                    }
                }
                tabs.put("combat", combatStyles);
            }
        
            // Magic tab details (group 218)
            Widget magicWidget = client.getWidget(218, 0);
            if (magicWidget != null) {
                List<String> spells = new ArrayList<>();
                Widget[] children = magicWidget.getChildren();
                if (children != null) {
                    for (Widget child : children) {
                        if (child != null && child.getText() != null && !child.getText().isEmpty()) {
                            spells.add(child.getText());
                        }
                    }
                }
                tabs.put("magic", spells);
            }
        
            // Prayers tab details (group 541)
            Widget prayerWidget = client.getWidget(541, 0);
            if (prayerWidget != null) {
                List<String> prayers = new ArrayList<>();
                Widget[] children = prayerWidget.getChildren();
                if (children != null) {
                    for (Widget child : children) {
                        if (child != null && child.getText() != null && !child.getText().isEmpty()) {
                            prayers.add(child.getText());
                        }
                    }
                }
                tabs.put("prayer", prayers);
            }
        
            // Achievement diaries (example: Ardougne diary varbits)
            Map<String, Object> diaries = new HashMap<>();
            try {
                diaries.put("ardougne_easy", client.getVarbitValue(11920));
                diaries.put("ardougne_medium", client.getVarbitValue(11921));
                diaries.put("ardougne_hard", client.getVarbitValue(11922));
                diaries.put("ardougne_elite", client.getVarbitValue(11923));
            } catch (Exception e) {
                // fallback
            }
            tabs.put("achievement_diaries", diaries);
            state.put("tabs", tabs);
        
        // Add enhanced interaction tracking
        if (lastInteraction != null) {
            state.put("last_interaction", lastInteraction);
        }
        
        // Add movement tracking
        if (lastMovement != null) {
            state.put("last_movement", lastMovement);
        }
        
        
        
        // Always update main runelite_gamestate.json
        String mainJsonFilename = config.outputPath();
        try (FileWriter writer = new FileWriter(mainJsonFilename)) {
            gson.toJson(state, writer);
        } catch (IOException e) {
            log.error("Failed to write main game state", e);
        }
        
        // Only save screenshots and per-tick JSONs if data saving is enabled
        if (config.enableDataSaving()) {
            try {
                Robot robot = new Robot();
                
                // Check if client is properly loaded
                if (client.getCanvas() == null) {
                    log.warn("Client canvas is null - client may not be fully loaded");
                    state.put("screenshot", "NO_CANVAS");
                    return;
                }
                
                log.debug("Client canvas found - bounds: {}x{}, location: ({}, {})", 
                    client.getCanvas().getWidth(), client.getCanvas().getHeight(),
                    client.getCanvas().getX(), client.getCanvas().getY());
                
                // Get the RuneScape client window bounds
                java.awt.Rectangle clientBounds = client.getCanvas().getBounds();
                java.awt.Point clientLocation = client.getCanvas().getLocationOnScreen();
                
                // Validate client bounds
                if (clientBounds.width <= 0 || clientBounds.height <= 0) {
                    log.warn("Invalid client bounds: {}x{}", clientBounds.width, clientBounds.height);
                    state.put("screenshot", "INVALID_BOUNDS");
                    return;
                }
                
                // Check if client window is visible and not minimized
                if (clientLocation.x < 0 || clientLocation.y < 0) {
                    log.warn("Client window appears to be off-screen or minimized: ({}, {})", clientLocation.x, clientLocation.y);
                    state.put("screenshot", "OFF_SCREEN");
                    return;
                }
                
                // Create a rectangle for just the client window
                Rectangle clientRect = new Rectangle(
                    clientLocation.x, 
                    clientLocation.y, 
                    clientBounds.width, 
                    clientBounds.height
                );
                
                // Capture only the client window area
                BufferedImage clientImage = robot.createScreenCapture(clientRect);
                
                java.io.File dir = new java.io.File(config.screenshotDir());
                if (!dir.exists()) dir.mkdirs();
                java.io.File screenshotFile = new java.io.File(dir, timestampStr + ".png");
                ImageIO.write(clientImage, "png", screenshotFile);
                state.put("screenshot", timestampStr + ".png");
                
                log.debug("Screenshot saved: {}x{} pixels at ({}, {})", 
                    clientRect.width, clientRect.height, clientLocation.x, clientLocation.y);
            } catch (AWTException | IOException e) {
                log.error("Failed to capture screenshot", e);
                state.put("screenshot", "ERROR");
            } catch (Exception e) {
                log.error("Unexpected error during screenshot capture", e);
                state.put("screenshot", "ERROR");
            }
            
            // Always save per-tick JSON
            String perTickDir = config.perTickJsonDir();
            java.io.File perTickDirFile = new java.io.File(perTickDir);
            if (!perTickDirFile.exists()) perTickDirFile.mkdirs();
            String perTickJsonFilename = perTickDir + timestampStr + ".json";
            try (FileWriter writer = new FileWriter(perTickJsonFilename)) {
                gson.toJson(state, writer);
            } catch (IOException e) {
                log.error("Failed to write per-tick game state", e);
            }
        }
    }

    /**
     * Get human-readable animation name from animation ID
     */
    private String getAnimationName(int animationId) {
        switch (animationId) {
            case -1: return "idle";
            case 808: return "walking";
            case 819: return "running";
            case 165: return "smelting";
            case 164: return "crafting";
            case 1249: return "banking";
            case 832: return "fishing";
            case 883: return "woodcutting";
            case 875: return "mining";
            case 137: return "cooking";
            case 133: return "herblore";
            case 896: return "fletching";
            case 1247: return "smithing";
            case 1248: return "firemaking";
            case 1250: return "thieving";
            case 1251: return "agility";
            case 1252: return "slayer";
            case 1253: return "hunter";
            case 1254: return "construction";
            case 1255: return "farming";
            case 1256: return "runecrafting";
            default: return "animation_" + animationId;
        }
    }
    
    /**
     * Get movement direction based on position change
     */
    private String getMovementDirection(int lastX, int lastY, int currentX, int currentY) {
        if (lastX == -1 || lastY == -1) return "unknown";
        
        int deltaX = currentX - lastX;
        int deltaY = currentY - lastY;
        
        if (deltaX == 0 && deltaY == 0) return "stationary";
        if (deltaX > 0 && deltaY == 0) return "east";
        if (deltaX < 0 && deltaY == 0) return "west";
        if (deltaX == 0 && deltaY > 0) return "north";
        if (deltaX == 0 && deltaY < 0) return "south";
        if (deltaX > 0 && deltaY > 0) return "northeast";
        if (deltaX < 0 && deltaY > 0) return "northwest";
        if (deltaX > 0 && deltaY < 0) return "southeast";
        if (deltaX < 0 && deltaY < 0) return "southwest";
        
        return "unknown";
    }
    
    /**
     * Add comprehensive mouse hover information to the gamestate
     * Captures everything under the mouse cursor
     */

} 