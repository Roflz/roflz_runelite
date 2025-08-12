package net.runelite.client.plugins.stateexporter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.GameTick;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

import javax.inject.Inject;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

@Slf4j
@PluginDescriptor(
    name = "State Exporter"
)
public class StateExporterPlugin extends Plugin {
    @Inject
    private Client client;

    @Inject
    private StateExporterConfig config;

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    @Subscribe
    public void onGameTick(GameTick event) {
        if (config.exportFrequency() != StateExporterConfig.ExportFrequency.EVERY_TICK) {
            return;
        }
        Map<String, Object> state = new HashMap<>();
        // Timestamp and context
        state.put("timestamp", System.currentTimeMillis());
        state.put("world", client.getWorld());
        state.put("region", client.getLocalPlayer() != null ? client.getLocalPlayer().getWorldLocation().getRegionID() : -1);
        // Player info
        if (config.includePlayer()) {
            Player player = client.getLocalPlayer();
            if (player != null) {
                Map<String, Object> playerMap = new HashMap<>();
                playerMap.put("name", player.getName());
                playerMap.put("world_x", player.getWorldLocation().getX());
                playerMap.put("world_y", player.getWorldLocation().getY());
                playerMap.put("plane", player.getWorldLocation().getPlane());
                playerMap.put("health", player.getHealthRatio());
                playerMap.put("animation", player.getAnimation());
                playerMap.put("run_energy", client.getEnergy());
                playerMap.put("prayer", client.getBoostedSkillLevel(Skill.PRAYER));
                playerMap.put("special_attack", client.getVar(VarPlayer.SPECIAL_ATTACK_PERCENT));
                playerMap.put("interacting", player.getInteracting() != null ? player.getInteracting().getName() : null);
                state.put("player", playerMap);
            }
        }
        // Camera info
        state.put("camera_x", client.getCameraX());
        state.put("camera_y", client.getCameraY());
        state.put("camera_z", client.getCameraZ());
        state.put("camera_pitch", client.getCameraPitch());
        state.put("camera_yaw", client.getCameraYaw());
        // Inventory
        if (config.includeInventory()) {
            ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
            if (inv != null) {
                List<Map<String, Object>> inventory = new ArrayList<>();
                for (Item item : inv.getItems()) {
                    Map<String, Object> itemMap = new HashMap<>();
                    itemMap.put("id", item.getId());
                    itemMap.put("quantity", item.getQuantity());
                    inventory.add(itemMap);
                }
                state.put("inventory", inventory);
            }
        }
        // Equipment
        ItemContainer equip = client.getItemContainer(InventoryID.EQUIPMENT);
        if (equip != null) {
            List<Map<String, Object>> equipment = new ArrayList<>();
            for (Item item : equip.getItems()) {
                Map<String, Object> itemMap = new HashMap<>();
                itemMap.put("id", item.getId());
                itemMap.put("quantity", item.getQuantity());
                equipment.add(itemMap);
            }
            state.put("equipment", equipment);
        }
        // Visible NPCs
        if (config.includeNpcs()) {
            List<Map<String, Object>> npcs = new ArrayList<>();
            for (NPC npc : client.getNpcs()) {
                if (npc.getWorldLocation() != null) {
                    Map<String, Object> npcMap = new HashMap<>();
                    npcMap.put("id", npc.getId());
                    npcMap.put("name", npc.getName());
                    npcMap.put("x", npc.getWorldLocation().getX());
                    npcMap.put("y", npc.getWorldLocation().getY());
                    npcMap.put("plane", npc.getWorldLocation().getPlane());
                    npcs.add(npcMap);
                }
            }
            state.put("npcs", npcs);
        }
        // Visible Game Objects (on all tiles)
        if (config.includeObjects()) {
            List<Map<String, Object>> objects = new ArrayList<>();
            Scene scene = client.getScene();
            Tile[][][] tiles = scene.getTiles();
            for (int plane = 0; plane < tiles.length; plane++) {
                for (int x = 0; x < tiles[plane].length; x++) {
                    for (int y = 0; y < tiles[plane][x].length; y++) {
                        Tile tile = tiles[plane][x][y];
                        if (tile == null) continue;
                        for (GameObject obj : tile.getGameObjects()) {
                            if (obj != null) {
                                Map<String, Object> objMap = new HashMap<>();
                                objMap.put("id", obj.getId());
                                ObjectComposition objDef = client.getObjectDefinition(obj.getId());
                                objMap.put("name", objDef != null ? objDef.getName() : null);
                                objMap.put("x", obj.getWorldLocation().getX());
                                objMap.put("y", obj.getWorldLocation().getY());
                                objMap.put("plane", obj.getWorldLocation().getPlane());
                                // Optionally: add isTree or examine text if needed
                                objects.add(objMap);
                            }
                        }
                    }
                }
            }
            state.put("game_objects", objects);
        }
        // Items on ground
        List<Map<String, Object>> groundItems = new ArrayList<>();
        Scene scene = client.getScene();
        Tile[][][] tiles = scene.getTiles();
        for (Tile[][] planeTiles : tiles) {
            for (Tile[] row : planeTiles) {
                for (Tile tile : row) {
                    if (tile == null) continue;
                    Collection<TileItem> groundItemsCollection = tile.getGroundItems();
                    if (groundItemsCollection != null) {
                        for (TileItem item : groundItemsCollection) {
                            Map<String, Object> itemMap = new HashMap<>();
                            itemMap.put("id", item.getId());
                            itemMap.put("quantity", item.getQuantity());
                            itemMap.put("x", tile.getWorldLocation().getX());
                            itemMap.put("y", tile.getWorldLocation().getY());
                            itemMap.put("plane", tile.getWorldLocation().getPlane());
                            groundItems.add(itemMap);
                        }
                    }
                }
            }
        }
        state.put("ground_items", groundItems);
        // Dialog/Widget info
        if (config.includeDialog()) {
            Widget dialog = client.getWidget(WidgetInfo.DIALOG_NPC_TEXT);
            if (dialog != null && !dialog.isHidden()) {
                state.put("dialog_text", dialog.getText());
            }
        }
        // Write to JSON file (pretty-printed, configurable path)
        try (FileWriter writer = new FileWriter(config.outputPath())) {
            gson.toJson(state, writer);
        } catch (IOException e) {
            log.error("Failed to write game state", e);
        }
    }
} 