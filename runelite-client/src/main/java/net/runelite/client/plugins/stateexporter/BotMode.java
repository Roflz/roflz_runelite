package net.runelite.client.plugins.stateexporter;

public enum BotMode {
    NONE("None", "D:\\repos\\bot_runelite_IL\\data"),
    BOT1("Bot1", "D:\\repos\\bot_runelite_IL\\data\\bot1"),
    BOT2("Bot2", "D:\\repos\\bot_runelite_IL\\data\\bot2"),
    BOT3("Bot3", "D:\\repos\\bot_runelite_IL\\data\\bot3");
    
    private final String displayName;
    private final String dataPath;
    
    BotMode(String displayName, String dataPath) {
        this.displayName = displayName;
        this.dataPath = dataPath;
    }
    
    public String getDisplayName() { return displayName; }
    public String getDataPath() { return dataPath; }
    
    /**
     * Get the gamestates subdirectory path for bot mode
     */
    public String getGamestatesPath() {
        if (this == NONE) {
            return dataPath; // Return main data path for NONE mode
        }
        return dataPath + "/gamestates";
    }
    
    @Override
    public String toString() { return displayName; }
}
