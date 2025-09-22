import { REST, Routes } from 'discord.js';
import * as dotenv from 'dotenv';
import * as fs from 'node:fs';
import * as path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

dotenv.config();

const commands = [];
const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const commandsPath = path.join(__dirname, 'commands');
const commandFiles = fs.readdirSync(commandsPath).filter(file => file.endsWith('.js'));

for (const file of commandFiles) {
  const filePath = path.join(commandsPath, file);
  const fileUrl = pathToFileURL(filePath).href;
  try {
    const command = await import(fileUrl);
    if ('data' in command && command.data) {
      commands.push(command.data.toJSON());
    } else {
      console.log(`[警告] ${filePath} 中的指令缺少 "data" 屬性或為空。`);
    }
  } catch (error) {
    console.error(`[錯誤] 無法載入指令 ${filePath}:`, error);
  }
}


const token = process.env.DISCORD_TOKEN;
const clientId = process.env.CLIENT_ID;

if (!token || !clientId) {
  throw new Error("找不到 DISCORD_TOKEN 或 CLIENT_ID，請檢查 .env 檔案！");
}

const rest = new REST({ version: '10' }).setToken(token);

(async () => {
  try {
    console.log(`正在註冊 ${commands.length} 個應用程式 (/) 指令。`);

    const data = await rest.put(
      Routes.applicationCommands(clientId),
      { body: commands },
    ) as any[];

    console.log(`✅ 成功重新載入 ${data.length} 個應用程式 (/) 指令。`);
  } catch (error) {
    console.error(error);
  }
})();
