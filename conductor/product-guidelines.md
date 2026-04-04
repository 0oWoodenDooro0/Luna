# Product Guidelines: Luna Discord RPG

## Architecture & Code Organization
The project codebase is explicitly separated into distinct packages to maintain modularity between different features/games:
- `luna.core`: Contains common application infrastructure, shared repositories (e.g., database connection), and base command interfaces.
- `luna.rpg`: Houses all logic, models, repositories, and commands specific to the RPG game. Any new RPG feature or command MUST be placed here.
- `luna.undercover`: Houses all logic and commands specific to the Undercover game. Any new Undercover feature MUST be placed here.

## Language & Locale
- **Primary Language:** Traditional Chinese (正體中文).
- **Implementation:** All bot messages, monster names, material names, and interface elements will be in Traditional Chinese.

## Tone & Personality
- **Systemic & Concise:** Communication should be clean and data-driven. The focus is on providing clear information about stats, combat results, and resource gains without unnecessary narrative fluff.

## User Experience (UX)
- **Primary Interaction:** Slash Commands (`/explore`, `/status`, `/upgrade`).
- **Visual Presentation:** Rich Embeds should be used to display character status, combat outcomes, and inventory details. Use consistent colors (e.g., Red for low health, Gold for rare loot, Green for status boosts).
- **Command Design:** Ensure commands are intuitive and categorized by function (Exploration, Management, System).

## Error Handling & Cooldowns
- **In-Character Responses:** When a player is on cooldown or lacks resources, the bot should respond in a way that matches the game's atmosphere (e.g., "你的角色正在休息中，還不能進行下一次探索。" instead of "Command on cooldown").
- **Cleanliness:** Use ephemeral messages for error responses to avoid cluttering the channel.

## Branding
- **Name:** The game is currently referred to as "Luna" or "Discord RPG".
- **Consistency:** Use consistent terminology for resources (🪵 木頭, 🪨 石頭, 🔗 金屬) and stats (血量 HP, 攻擊 ATK, 防禦 DEF, 速度 SPD).
