import { SlashCommandBuilder, CommandInteraction } from 'discord.js';
import { Poll } from '../models/Poll.js'; // 注意路徑和 .js 副檔名

// 我們將在主檔案中用一個 Map 來儲存進行中的投票
// Key: interaction.id, Value: Poll instance
export const activePolls = new Map<string, Poll>();

export const data = new SlashCommandBuilder()
  .setName('vote')
  .setDescription('發起一個投票')
  .addStringOption(option =>
    option.setName('topic').setDescription('投票的主題').setRequired(true))
  .addStringOption(option =>
    option.setName('option1').setDescription('選項 1').setRequired(true))
  .addStringOption(option =>
    option.setName('option2').setDescription('選項 2').setRequired(true))
  .addStringOption(option =>
    option.setName('option3').setDescription('選項 3').setRequired(false))
  .addStringOption(option =>
    option.setName('option4').setDescription('選項 4').setRequired(false));

export async function execute(interaction: CommandInteraction) {
  if (!interaction.isChatInputCommand()) return;

  const topic = interaction.options.getString('topic', true);
  const optionsInput = [
    interaction.options.getString('option1', true),
    interaction.options.getString('option2', true),
    interaction.options.getString('option3'),
    interaction.options.getString('option4'),
  ].filter((o): o is string => o !== null);

  const poll = new Poll(topic, optionsInput);

  // 儲存這個投票實例，以便在按鈕點擊時能找到它
  activePolls.set(interaction.id, poll);

  await interaction.reply(poll.createMessagePayload());
}
