import { REST, Routes, SlashCommandBuilder } from 'discord.js';
import * as dotenv from 'dotenv';

dotenv.config();

const commands = [
  new SlashCommandBuilder()
    .setName('poll')
    .setDescription('投票')
    .addSubcommand(subcommand =>
      subcommand.setName('create')
        .setDescription('發起投票')
        .addStringOption(option =>
          option.setName('topic')
            .setDescription('題目')
            .setRequired(true))
        .addStringOption(option =>
          option.setName('option1')
            .setDescription('選項 1')
            .setRequired(true))
        .addStringOption(option =>
          option.setName('option2')
            .setDescription('選項 2')
            .setRequired(true))),
  new SlashCommandBuilder()
    .setName('vote')
    .setDescription('進行投票')
    .addSubcommand(subcommand =>
      subcommand.setName('cast')
        .setDescription('對一個現有的投票進行投票')
        .addStringOption(option =>
          option.setName('poll-id')
            .setDescription('投票ID')
            .setRequired(true))
        .addIntegerOption(option =>
          option.setName('option')
            .setDescription('投票編號')
            .setRequired(true)
            .setMinValue(1)
            .setMaxValue(2)))
].map(command => command.toJSON());

const token = process.env.DISCORD_TOKEN;
const clientId = process.env.CLIENT_ID;

if (!token || !clientId) {
  throw new Error("找不到 DISCORD_TOKEN 或 CLIENT_ID，請檢查 .env 檔案！");
}

const rest = new REST({ version: '10' }).setToken(token);

(async () => {
  try {
    console.log('正在重新註冊斜線指令...');

    await rest.put(
      Routes.applicationCommands(clientId),
      { body: commands },
    );

    console.log('✅ 成功註冊斜線指令！');
  } catch (error) {
    console.error(error);
  }
})();
