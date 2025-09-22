import { SlashCommandBuilder, CommandInteraction, EmbedBuilder, MessageFlags } from 'discord.js';

export const data = new SlashCommandBuilder()
  .setName('help')
  .setDescription('顯示所有可用指令的說明');

export async function execute(interaction: CommandInteraction) {
  const helpEmbed = new EmbedBuilder()
    .setColor(0x0099FF)
    .setTitle('🤖 Luna 指令說明')
    .setDescription('嗨！這是我目前會的所有指令：')
    .addFields(
      { name: '❓ 呼叫此選單', value: '`/help` - 顯示這則說明訊息。' },
      { name: '💰 點數管理', value: '`/points check [user]` - 查詢你或其他人的點數餘額。' },
      { name: '🎉 發起賭注', value: '`/bet create <topic> <option1> <option2> [days] [hours] [minutes]` - 建立一個新的賭注活動。' },
      { name: '💸 參與下注', value: '`/bet place <bet-id> <option> <amount>` - 對一個進行中的賭注下注。' },
      { name: '✅ 結算賭注 (限管理員)', value: '`/bet resolve <bet-id> <winning-option>` - 宣布獲勝方並自動派彩。' },
    )
    .setFooter({ text: '請將 < > 中的內容替換為實際參數' });

  await interaction.reply({
    embeds: [helpEmbed],
    flags: [MessageFlags.Ephemeral] // Ephemeral means only the user who ran the command can see it
  });
}
