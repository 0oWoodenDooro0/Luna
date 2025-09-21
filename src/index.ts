import { Client, Events, GatewayIntentBits, Collection, BaseInteraction } from 'discord.js';
import * as dotenv from 'dotenv';
import * as fs from 'node:fs';
import * as path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { activePolls } from './commands/vote.js'; // 引入 activePolls

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

dotenv.config();

// 擴充 Client 類別，使其可以存放 commands 集合
class CustomClient extends Client {
  commands = new Collection<string, any>();
}

const client = new CustomClient({
  intents: [GatewayIntentBits.Guilds],
});

// --- 動態載入指令 ---
const commandsPath = path.join(__dirname, 'commands');
const commandFiles = fs.readdirSync(commandsPath).filter(file => file.endsWith('.js'));

for (const file of commandFiles) {
  const filePath = path.join(commandsPath, file);
  const fileUrl = pathToFileURL(filePath).href;
  const command = await import(fileUrl);

  if ('data' in command && 'execute' in command) {
    client.commands.set(command.data.name, command);
    console.log(`成功載入 ${command.data.name}`);
  } else {
    console.log(`[警告] ${filePath} 中的指令缺少 "data" 或 "execute" 屬性。`);
  }
}

// --- 事件處理 ---
client.once(Events.ClientReady, c => {
  console.log(`✅ 準備好了！已登入為 ${c.user.tag}`);
});

client.on(Events.InteractionCreate, async (interaction: BaseInteraction) => {
  // 處理斜線指令
  if (interaction.isChatInputCommand()) {
    const command = (interaction.client as CustomClient).commands.get(interaction.commandName);
    if (!command) {
      console.error(`找不到指令 ${interaction.commandName}。`);
      return;
    }
    try {
      await command.execute(interaction);
    } catch (error) {
      console.error(error);
      await interaction.reply({ content: '執行此指令時發生錯誤！', ephemeral: true });
    }
  }
  // 處理按鈕點擊 (下一步實作)
  else if (interaction.isButton()) {
    // 這裡是我們之後要加入按鈕處理邏輯的地方
  }
});

const token = process.env.DISCORD_TOKEN;
client.login(token);
