package net.runelite.client.plugins.stateexporter;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("stateexporter")
public interface StateExporterConfig extends Config
{
    // Add config options here as needed for the State Exporter plugin

    @ConfigItem(
        keyName = "outputPath",
        name = "Output Path",
        description = "Path to write the exported game state JSON file"
    )
    default String outputPath() { return "C:/temp/runelite_gamestate.json"; }

    @ConfigItem(
        keyName = "exportFrequency",
        name = "Export Frequency",
        description = "How often to export state (every tick, on event, manual)"
    )
    default ExportFrequency exportFrequency() { return ExportFrequency.EVERY_TICK; }

    @ConfigItem(
        keyName = "includePlayer",
        name = "Include Player State",
        description = "Include player info in export"
    )
    default boolean includePlayer() { return true; }

    @ConfigItem(
        keyName = "includeObjects",
        name = "Include Game Objects",
        description = "Include game objects (trees, banks, etc.)"
    )
    default boolean includeObjects() { return true; }

    @ConfigItem(
        keyName = "includeNpcs",
        name = "Include NPCs",
        description = "Include NPCs in export"
    )
    default boolean includeNpcs() { return true; }

    @ConfigItem(
        keyName = "includeInventory",
        name = "Include Inventory",
        description = "Include inventory contents"
    )
    default boolean includeInventory() { return true; }

    @ConfigItem(
        keyName = "includeDialog",
        name = "Include Dialogs/Widgets",
        description = "Include open dialog and widget info"
    )
    default boolean includeDialog() { return true; }

    // Add more config options as needed for extensibility

    enum ExportFrequency
    {
        EVERY_TICK,
        ON_EVENT,
        MANUAL
    }
}