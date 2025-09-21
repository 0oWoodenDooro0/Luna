// src/commands/poll.ts
import { SlashCommandBuilder, CommandInteraction } from 'discord.js';
import { Poll } from '../models/Poll.js';
import { savePoll } from '../database.js'; // <-- 1. Import the savePoll function

// We no longer need the in-memory map
// const polls = new Map<string, Poll>(); 

export const data = new SlashCommandBuilder()
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
          .setRequired(true)));
export async function execute(interaction: CommandInteraction) {
  if (!interaction.isChatInputCommand()) return;

  if (interaction.commandName === 'poll' && interaction.options.getSubcommand() === 'create') {
    const topic = interaction.options.getString('topic', true);
    const optionsInput = [
      interaction.options.getString('option1', true),
      interaction.options.getString('option2', true),
      interaction.options.getString('option3'),
      interaction.options.getString('option4'),
    ].filter((o): o is string => o !== null);

    const poll = new Poll(topic, optionsInput);

    const message = await interaction.reply({ ...poll.createMessagePayload(), fetchReply: true });

    poll.id = message.id;

    await message.edit(poll.createMessagePayload());

    // 2. Replace the TODO with our new save function
    savePoll(poll);
    console.log(`Poll ${poll.id} created and saved to the database.`);
  }
}
