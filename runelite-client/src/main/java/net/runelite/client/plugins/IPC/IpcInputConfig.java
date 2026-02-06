package net.runelite.client.plugins.ipcinput;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("ipcinput")
public interface IpcInputConfig extends Config
{
    @ConfigItem(
            keyName = "port",
            name = "Port",
            description = "Localhost port to listen on",
            position = 1
    )
    default int port()
    {
        return 17000;
    }

    @ConfigItem(
        keyName = "mode",
        name = "Input mode",
        description = "AWT dispatch (background-capable) or Robot (foreground only)",
        position = 0
    )
    default String mode()
    {
        return "AWT"; // "AWT" or "ROBOT"
    }

    // IpcInputConfig.java
    @ConfigItem(
        keyName = "hoverDelayMs",
        name = "Hover delay (ms)",
        description = "Fixed delay between MOVE and CLICK so scene hover resolves",
        position = 2
    )
    default int hoverDelayMs()
    {
        return 10; // set 5–15ms to taste
    }

}
