# State Exporter Plugin

A RuneLite plugin that exports comprehensive game state data for bot training and analysis.

## Features

### Player (15 features)
- World coordinates (x, y, plane)
- Health ratio
- Animation ID and name
- Movement status and direction
- Run energy
- Prayer points
- Special attack percentage
- Last action and target
- Last interaction details
- Last movement details

### Camera (6 features)
- Camera coordinates (x, y, z)
- Camera pitch and yaw
- Camera scale (zoom level)

### Inventory (28+ features)
- Item IDs, quantities, and names
- Item properties (members, tradeable, stackable, etc.)
- Item actions and equipment slots

### Bank (Variable features)
- Bank open/closed status
- Quantity selection mode (1, 5, 10, X, All)
- Bank contents with item details
- Item positions and canvas coordinates

### Game Objects (Variable features)
- Object IDs, names, and properties
- World coordinates and distances
- Object actions and interactivity
- Special tracking for bank booths and furnaces

### NPCs (Variable features)
- NPC IDs, names, and properties
- World coordinates and distances
- Combat levels and interactivity
- Focus on bankers and important NPCs

### Minimap (4 features)
- Base coordinates
- Map regions
- Local player region

### Skills (2 features)
- Crafting level and experience

### Chatbox (Variable features)
- Game messages and level up notifications
- Recent chat history

### Tabs (Variable features)
- Current active tab
- Tab contents (inventory, equipment, combat, magic, prayer)
- Achievement diary progress

### Mouse and Interaction (Variable features)
- Mouse canvas position
- Objects under cursor
- Selected scene tile (last right-clicked)
- Closest structures to player

### Phase Context (5 features)
- Current activity phase
- Phase duration and gamestate count
- Crafting status

### Widget Tracking (Variable features)
- Crafting interface widgets
- Bank item positions
- Bank close button

## Total Features: 100+ (varies based on game state)

## Configuration

- **Output Path**: Main gamestate JSON file location
- **Screenshot Directory**: Where screenshots are saved
- **Per-tick JSON Directory**: Where individual tick data is saved
- **Enable Data Saving**: Toggle for comprehensive data collection
- **Enable Screenshots**: Toggle for screenshot capture
- **Bot Mode**: Switch between training and bot operation modes

## Bot Modes

- **NONE**: Normal training data collection
- **TRAINING**: Training mode with rolling buffer
- **LIVE**: Live bot operation with rolling buffer

## Data Format

The plugin exports data in JSON format with the following structure:
- Timestamp and world information
- Player state and movement
- Camera and view information
- Inventory and bank contents
- Game objects and NPCs
- UI state and widgets
- Mouse and interaction data
- Phase and context information

## Usage

1. Install the plugin in RuneLite
2. Configure output paths and options
3. Start the game and begin activities
4. Data is automatically exported each game tick
5. Use the exported data for bot training and analysis

## Notes

- Camera scale provides zoom level information
- Mouse cursor tracking shows what's under the cursor
- Selected tile tracking captures last right-clicked location
- Closest structures are prioritized by distance
- All items, objects, and NPCs include composition data
- Phase detection automatically identifies current activity
- Rolling buffer maintains recent gamestates for bot operation
