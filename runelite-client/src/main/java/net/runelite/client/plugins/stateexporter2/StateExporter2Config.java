package net.runelite.client.plugins.stateexporter2;

import net.runelite.client.config.*;

import java.io.File;

@ConfigGroup("stateexporter2")
public interface StateExporter2Config extends Config
{
    @ConfigSection(
        name = "Directory Settings",
        description = "Configure where gamestate JSONs are saved",
        position = 0
    )
    String directorySection = "directorySection";

    @Range(min = 10, max = 600)
    @ConfigItem(
        keyName = "exportIntervalMs",
        name = "Export interval (ms)",
        description = "How often to export the game state"
    )
    default int exportIntervalMs()
    {
        return 250; // default; change in plugin hub config UI
    }

    @ConfigItem(
        keyName = "gamestatesDirectory",
        name = "Gamestates Directory",
        description = "Directory to save gamestate JSON files",
        section = directorySection,
        position = 1
    )
    default String gamestatesDirectory()
    {
        return getDefaultGamestatesDirectory();
    }

    @ConfigItem(
            keyName = "maxGamestateFiles",
            name = "Max gamestate files",
            description = "Keep only the most recent N gamestate JSON files in the session folder"
    )
    default int maxGamestateFiles()
    {
        return 50; // default cap
    }

    @ConfigItem(
            keyName = "objectScanRadius",
            name = "Object scan radius",
            description = "Tile radius around the player to scan for game objects / GE booths.",
            position = 42
    )
    default int objectScanRadius() {
        return 12; // sensible default; your code bounds it to [5..30]
    }

    @ConfigItem(
        keyName = "refreshDirectory",
        name = "Refresh Directory",
        description = "Click to refresh the gamestates directory to the latest recording session",
        section = directorySection,
        position = 2
    )
    default boolean refreshDirectory()
    {
        return false;
    }

    @ConfigItem(
        keyName = "refreshDirectory",
        name = "",
        description = "",
        section = directorySection,
        position = 2,
        hidden = true
    )
    void setRefreshDirectory(boolean value);

    @ConfigSection(
        name = "Export Settings",
        description = "Configure JSON export behavior",
        position = 1
    )
    String exportSection = "exportSection";

    @ConfigItem(
        keyName = "enableExport",
        name = "Enable Export",
        description = "Enable gamestate JSON export",
        section = exportSection,
        position = 1
    )
    default boolean enableExport()
    {
        return true;
    }

    @ConfigItem(
        keyName = "maxNPCs",
        name = "Max NPCs",
        description = "Maximum number of closest NPCs to include",
        section = exportSection,
        position = 3
    )
    default int maxNPCs()
    {
        return 5;
    }

    @ConfigItem(
        keyName = "maxGameObjects",
        name = "Max Game Objects",
        description = "Maximum number of closest game objects to include",
        section = exportSection,
        position = 4
    )
    default int maxGameObjects()
    {
        return 5;
    }

    @ConfigItem(
        keyName = "prioritizedNPCs",
        name = "Prioritized NPCs",
        description = "NPC names to always include if detected (comma-separated)",
        section = exportSection,
        position = 5
    )
    default String prioritizedNPCs()
    {
        return "";
    }

    @ConfigItem(
        keyName = "prioritizedGameObjects",
        name = "Prioritized Game Objects",
        description = "Game object names to always include if detected (comma-separated)",
        section = exportSection,
        position = 6
    )
    default String prioritizedGameObjects()
    {
        return "";
    }


    /**
     * Get the default gamestates directory (latest recording session)
     */
    static String getDefaultGamestatesDirectory()
    {
        try
        {
            // Look for the latest recording session directory in the exact path
            File recordingSessionsDir = new File("D:\\repos\\bot_runelite_IL\\data\\recording_sessions");
            if (!recordingSessionsDir.exists())
            {
                return "D:\\repos\\bot_runelite_IL\\data\\recording_sessions\\test\\gamestates";
            }

            File[] sessionDirs = recordingSessionsDir.listFiles(File::isDirectory);
            if (sessionDirs == null || sessionDirs.length == 0)
            {
                return "D:\\repos\\bot_runelite_IL\\data\\recording_sessions\\test\\gamestates";
            }

            // Find the latest directory by name (assuming timestamp format)
            File latestDir = null;
            for (File dir : sessionDirs)
            {
                if (latestDir == null || dir.getName().compareTo(latestDir.getName()) > 0)
                {
                    latestDir = dir;
                }
            }

            if (latestDir != null)
            {
                return latestDir.getPath() + "\\gamestates";
            }

            return "D:\\repos\\bot_runelite_IL\\data\\recording_sessions\\test\\gamestates";
        }
        catch (Exception e)
        {
            return "D:\\repos\\bot_runelite_IL\\data\\recording_sessions\\test\\gamestates";
        }
    }
}
