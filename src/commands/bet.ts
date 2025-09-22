import { SlashCommandBuilder, CommandInteraction, TextChannel, MessageFlags, Client, DiscordAPIError } from 'discord.js';
import { Bet } from '../models/Bet.js';
import { saveBet, getBet, getUserPoints, placeBetTransaction, getWagersForBet, payoutWinnings } from '../database.js';

export const data = new SlashCommandBuilder()
  .setName('bet')
  .setDescription('賭注')
  .addSubcommand(subcommand =>
    subcommand.setName('create')
      .setDescription('發起賭注')
      .addStringOption(option =>
        option.setName('topic')
          .setDescription('主題')
          .setRequired(true))
      .addStringOption(option =>
        option.setName('option1')
          .setDescription('選項 1')
          .setRequired(true))
      .addStringOption(option =>
        option.setName('option2')
          .setDescription('選項 2')
          .setRequired(true))
      .addIntegerOption(option =>
        option.setName('days')
          .setDescription('持續【天】數')
          .setRequired(false)
          .setMinValue(0))
      .addIntegerOption(option =>
        option.setName('hours')
          .setDescription('持續【小時】數')
          .setRequired(false)
          .setMinValue(0)
          .setMaxValue(23))
      .addIntegerOption(option =>
        option.setName('minutes')
          .setDescription('持續【分鐘】數')
          .setRequired(false)
          .setMinValue(0)
          .setMaxValue(59)))
  .addSubcommand(subcommand =>
    subcommand.setName('place')
      .setDescription('對一個賭注下注')
      .addStringOption(option =>
        option.setName('bet-id')
          .setDescription('賭注ID')
          .setRequired(true))
      .addIntegerOption(option =>
        option.setName('option')
          .setDescription('您想下注的選項編號 (1 或 2)')
          .setRequired(true)
          .setChoices(
            { name: '1', value: 1 },
            { name: '2', value: 2 },
          ))
      .addIntegerOption(option =>
        option.setName('amount')
          .setDescription('您要下注的點數')
          .setRequired(true)
          .setMinValue(1)))
  .addSubcommand(subcommand => // <-- 加入這個子指令
    subcommand
      .setName('resolve')
      .setDescription('【管理員】結算一個賭注並派彩')
      .addStringOption(option =>
        option.setName('bet-id')
          .setDescription('要結算的賭注訊息 ID')
          .setRequired(true))
      .addIntegerOption(option =>
        option.setName('winning-option')
          .setDescription('獲勝的是哪個選項？')
          .setRequired(true)
          .setChoices(
            { name: '1', value: 1 },
            { name: '2', value: 2 },
          )));

export async function execute(interaction: CommandInteraction) {
  if (!interaction.isChatInputCommand()) return;

  const subcommand = interaction.options.getSubcommand();

  switch (subcommand) {
    case 'create': {
      const topic = interaction.options.getString('topic', true);
      const optionsInput = [
        interaction.options.getString('option1', true),
        interaction.options.getString('option2', true)
      ];

      if (!interaction.channelId) {
        await interaction.reply({ content: '無法在此頻道建立投票。', flags: [MessageFlags.Ephemeral] });
        return;
      }

      const days = interaction.options.getInteger('days') ?? 0;
      const hours = interaction.options.getInteger('hours') ?? 0;
      const minutes = interaction.options.getInteger('minutes') ?? 0;

      const totalMilliseconds = (days * 24 * 60 * 60 + hours * 60 * 60 + minutes * 60) * 1000;
      const endTime = totalMilliseconds > 0 ? Date.now() + totalMilliseconds : null;

      const bet = new Bet(interaction.channelId, topic, optionsInput, endTime);

      const response = await interaction.reply({ embeds: [bet.createBetEmbed()] });
      const message = await response.fetch();

      bet.id = message.id;

      await message.edit({ embeds: [bet.createBetEmbed()] });

      saveBet(bet);
      break;
    }
    case 'place': {
      const betId = interaction.options.getString('bet-id', true);
      const optionNumber = interaction.options.getInteger('option', true);
      const amount = interaction.options.getInteger('amount', true);
      const optionIndex = optionNumber - 1;

      const bet = getBet(betId);
      if (!bet) {
        return interaction.reply({ content: '找不到具有該 ID 的賭注。', flags: [MessageFlags.Ephemeral] });
      }
      if (!bet.isActive) {
        return interaction.reply({ content: '此賭注已結束，無法下注。', flags: [MessageFlags.Ephemeral] });
      }

      if (bet.endTime && Date.now() > bet.endTime) {
        return interaction.reply({ content: '此賭注的下注時間已截止。', flags: [MessageFlags.Ephemeral] });
      }

      const result = placeBetTransaction(interaction.user.id, betId, optionIndex, amount);

      if (result === 'insufficient_points') {
        const userPoints = getUserPoints(interaction.user.id);
        return interaction.reply({ content: `您的點數不足！您目前有 ${userPoints} 點，但您試圖下注 ${amount} 點。`, flags: [MessageFlags.Ephemeral] });
      } else if (result === 'error') {
        return interaction.reply({ content: '下注時發生未預期的錯誤，請稍後再試。', flags: [MessageFlags.Ephemeral] });
      }

      // 3. Update the original bet message with new odds
      const updatedBet = getBet(betId)!; // Refetch the bet to get the new totals
      await updateBetMessage(interaction.client, updatedBet);

      // 4. Confirm to the user
      await interaction.reply({ content: `您已成功在「${updatedBet.options[optionIndex]?.label}」上下注 ${amount} 點！`, flags: [MessageFlags.Ephemeral] });
      break;
    }
    case 'resolve': {
      const betId = interaction.options.getString('bet-id', true);
      const winningOptionNumber = interaction.options.getInteger('winning-option', true);
      const winningOptionIndex = winningOptionNumber - 1;

      const bet = getBet(betId);
      if (!bet) {
        return interaction.reply({ content: '找不到賭注。', flags: [MessageFlags.Ephemeral] });
      }
      if (!bet.isActive) {
        return interaction.reply({ content: '此賭注已結算。', flags: [MessageFlags.Ephemeral] });
      }

      // --- 計算派彩 ---
      const allWagers = getWagersForBet(betId);
      const winningPool = bet.options[winningOptionIndex]?.pointsPool ?? 0;
      const totalPool = bet.options.reduce((sum, opt) => sum + opt.pointsPool, 0);

      const payouts: { userId: string, amount: number }[] = [];
      if (winningPool > 0) {
        const winners = allWagers.filter(w => w.optionIndex === winningOptionIndex);
        for (const winner of winners) {
          const proportion = winner.amount / winningPool; // 計算該贏家在獲勝方資金池中的佔比
          const winnings = Math.floor(proportion * totalPool); // 按佔比分配總資金池
          payouts.push({ userId: winner.userId, amount: winnings });
        }
      }

      // --- 更新資料庫 ---
      bet.isActive = false;
      bet.winningOption = winningOptionIndex;
      saveBet(bet); // 將賭注標記為已結束
      if (payouts.length > 0) {
        payoutWinnings(payouts); // 執行派彩交易
      }

      // --- 更新 Discord 訊息 ---
      await updateBetMessage(interaction.client, bet);

      if (payouts.length > 0) {
        // 1. 建立一個包含所有贏家 tag 的字串
        const winnerMentions = payouts.map(p => `<@${p.userId}>`).join(' ');

        // 2. 建立通知訊息內容
        const notificationMessage = `🎉 賭注 **「${bet.topic}」** 已結算！獲勝方為 **「${bet.options[winningOptionIndex]?.label}」**！\n\n恭喜贏家：${winnerMentions}`;

        // 3. 在同一個頻道發送通知訊息
        try {
          const channel = await interaction.client.channels.fetch(bet.channelId) as TextChannel;
          if (channel) {
            await channel.send(notificationMessage);
          }
        } catch (error) {
          console.error(`無法發送賭注 ${betId} 的獲勝通知：`, error);
        }
      }

      await interaction.reply({ content: `賭注 ${bet.topic} 已結算！獲勝方為「${bet.options[winningOptionIndex]?.label}」。總計已向 ${payouts.length} 位贏家派發 ${totalPool} 點。`, flags: [MessageFlags.Ephemeral] });
    }
      break;
  }
}

/**
 * A helper function to safely update a bet message.
 * If the original message is deleted, it posts a new one.
 * @param client The Discord client instance.
 * @param bet The bet object containing the message and channel ID.
 */
async function updateBetMessage(client: Client, bet: Bet) {
  try {
    const channel = await client.channels.fetch(bet.channelId) as TextChannel;
    if (channel) {
      const message = await channel.messages.fetch(bet.id);
      if (message) {
        await message.edit({ embeds: [bet.createBetEmbed()] });
      }
    }
  } catch (error) {
    if (error instanceof DiscordAPIError && error.code === 10008) { // 10008 = Unknown Message
      try {
        const channel = await client.channels.fetch(bet.channelId) as TextChannel;
        if (channel) {
          await channel.send({ embeds: [bet.createBetEmbed()] });
        }
      } catch (postError) {
        console.error(`Failed to post new message for bet ${bet.id}:`, postError);
      }
    }
  }
}
