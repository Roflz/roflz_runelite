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
    default String outputPath() { return "D:\\repos\\bot_runelite_IL\\data\\runelite_gamestate.json"; }

    @ConfigItem(
        keyName = "screenshotDir",
        name = "Screenshot Directory",
        description = "Directory to save screenshots with matching timestamp filenames"
    )
    default String screenshotDir() { return "D:\\repos\\bot_runelite_IL\\data\\runelite_screenshots\\"; }

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
    default String perTickJsonDir() { return "D:\\repos\\bot_runelite_IL\\data\\gamestates\\"; }

    @ConfigItem(
        keyName = "enableScreenshots",
        name = "Enable Screenshots",
        description = "If enabled, the plugin will capture and save screenshots. Disable this for better performance when you only need gamestate data."
    )
    default boolean enableScreenshots() { return true; }

    @ConfigItem(
        keyName = "botMode",
        name = "Bot Mode",
        description = "Select bot mode to save gamestate data to specific bot folders. None = normal training data collection."
    )
    default BotMode botMode() { return BotMode.NONE; }
}