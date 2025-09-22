import { SlashCommandBuilder, CommandInteraction, MessageFlags } from 'discord.js';
import { getUserPoints, setUserPoints } from '../database.js';

export const data = new SlashCommandBuilder()
  .setName('points')
  .setDescription('點數')
  .addSubcommand(subcommand =>
    subcommand.setName('check')
      .setDescription('查看你的點數'))
  .addSubcommand(subcommand =>
    subcommand.setName('give')
      .setDescription('【管理員】給予使用者點數')
      .addUserOption(option =>
        option.setName('user').setDescription('要給予點數的使用者')
          .setRequired(true))
      .addIntegerOption(option =>
        option.setName('amount').setDescription('要給予的點數量')
          .setRequired(true))
  );

export async function execute(interaction: CommandInteraction) {
  if (!interaction.isChatInputCommand()) return;

  const subcommand = interaction.options.getSubcommand();

  if (subcommand === 'check') {
    const points = getUserPoints(interaction.user.id);
    await interaction.reply({
      content: `你目前有 ${points} 點。`,
      flags: [MessageFlags.Ephemeral]
    });
  } else if (subcommand === 'give') {
    const targetUser = interaction.options.getUser('user', true);
    const amount = interaction.options.getInteger('amount', true);
    const currentPoints = getUserPoints(targetUser.id);
    const newTotal = currentPoints + amount;

    setUserPoints(targetUser.id, newTotal);

    await interaction.reply({
      content: `已給予 ${targetUser.username} ${amount} 點。他們現在的總點數為 ${newTotal}。`,
      flags: [MessageFlags.Ephemeral]
    });
  }
}
