import { Client, TextChannel, EmbedBuilder } from 'discord.js';
import * as dotenv from 'dotenv';

dotenv.config();

const logChannelId = process.env.LOG_CHANNEL_ID;

export async function logAction(client: Client, title: string, message: string) {
  if (!logChannelId) {
    console.log(`[日誌] ${title}: ${message}`);
    return;
  }

  try {
    const channel = await client.channels.fetch(logChannelId) as TextChannel;
    if (channel) {
      const logEmbed = new EmbedBuilder()
        .setColor(0x57F287) // 綠色
        .setTitle(`📝 ${title}`)
        .setDescription(message)
        .setTimestamp();

      await channel.send({ embeds: [logEmbed] });
    }
  } catch (error) {
    console.error('發送日誌時發生錯誤:', error);
  }
}
