package net.runelite.client.plugins.stateexporter;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("stateexporter")
public interface StateExporterConfig extends Config
{
    @ConfigItem(
        keyName = "outputPath",
        name = "Output Path",
        description = "Path to write the exported game state JSON file"
    )
    default String outputPath() { return "D:\\cursor_projects\\osrs_learner\\bot_runelite_IL\\data\\runelite_gamestate.json"; }

    @ConfigItem(
        keyName = "screenshotDir",
        name = "Screenshot Directory",
        description = "Directory to save screenshots with matching timestamp filenames"
    )
    default String screenshotDir() { return "D:\\cursor_projects\\osrs_learner\\bot_runelite_IL\\data\\runelite_screenshots\\"; }

    @ConfigItem(
        keyName = "enableDataSaving",
        name = "Enable Data Saving",
        description = "If enabled, the plugin will save screenshots and per-tick JSONs to the directories used by the IL data processing module. The main runelite_gamestate.json is always updated."
    )
    default boolean enableDataSaving() { return true; }

    @ConfigItem(
        keyName = "perTickJsonDir",
        name = "Per-Tick JSON Directory",
        description = "Directory to save per-tick game state JSONs for IL training"
    )
    default String perTickJsonDir() { return "D:\\cursor_projects\\osrs_learner\\bot_runelite_IL\\data\\gamestates\\"; }
}