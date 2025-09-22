import { EmbedBuilder } from 'discord.js';

interface BetOption {
  label: string;
  pointsPool: number;
  bettorCount: number;
  maxBet: number;
}

// This interface defines the simple object structure for saving to JSON
export interface BetJSON {
  id: string;
  channelId: string;
  topic: string;
  options: BetOption[];
  endTime: number | null;
  isActive: boolean;
  winningOption: number | null;
}

export class Bet {
  public id: string; // The Discord message ID
  public channelId: string;
  public topic: string;
  public options: BetOption[];
  public endTime: number | null;
  public isActive: boolean;
  public winningOption: number | null;

  constructor(channelId: string, topic: string, options: string[], endTime: number | null = null, id: string = '') {
    this.id = id;
    this.channelId = channelId;
    this.topic = topic;
    this.options = options.map(opt => ({ label: opt, pointsPool: 0, bettorCount: 0, maxBet: 0 }));
    this.endTime = endTime;
    this.isActive = true;
    this.winningOption = null;
  }

  // --- Persistence Methods ---

  // Converts the Poll object into a simple JSON object for saving
  toJSON(): BetJSON {
    return {
      id: this.id,
      channelId: this.channelId,
      topic: this.topic,
      options: this.options,
      endTime: this.endTime,
      isActive: this.isActive,
      winningOption: this.winningOption,
    };
  }

  // Creates a Poll instance from a simple JSON object
  static fromJSON(data: BetJSON): Bet {
    const poll = new Bet(data.channelId, data.topic, [], data.endTime, data.id);
    poll.options = data.options;
    poll.isActive = data.isActive;
    return poll;
  }

  public endPoll() {
    this.isActive = false;
  }

  // --- Display Methods ---

  createBetEmbed() {
    const embed = new EmbedBuilder()
      .setColor(this.isActive ? 0x0099FF : 0x808080)
      .setTitle(this.topic)
      .setFooter({ text: `Bet ID: ${this.id}` });

    const barWidth = 20;
    const option1Char = '🟦';
    const option2Char = '🟥';

    const option1 = this.options[0];
    const option2 = this.options[1];
    const totalPool = (option1?.pointsPool ?? 0) + (option2?.pointsPool ?? 0);

    let bar = '';
    if (totalPool === 0) {
      const halfWidth = Math.floor(barWidth / 2);
      bar = option1Char.repeat(halfWidth) + option2Char.repeat(barWidth - halfWidth);
    } else if (option1 && option2) {
      const option1Blocks = Math.round((option1.pointsPool / totalPool) * barWidth);
      const option2Blocks = barWidth - option1Blocks;
      bar = option1Char.repeat(option1Blocks) + option2Char.repeat(option2Blocks);
    }

    let description = '**目前賭盤:**\n';
    description += `${bar}\n\n`;

    this.options.forEach((opt, index) => {
      const char = index === 0 ? option1Char : option2Char;
      const odds = totalPool > 0 && opt.pointsPool > 0 ? (totalPool / opt.pointsPool).toFixed(2) : '1.00';
      description += `${char} **${opt.label}**\n`;
      description += `點數池: ${opt.pointsPool} (賠率: 1:${odds})\n`;
      description += `人數: ${opt.bettorCount} | 最高單注: ${opt.maxBet}\n`;
    });

    if (this.isActive) {
      description += '\n使用 `/bet place` 來下注！\n';
    }

    embed.setDescription(description);

    if (!this.isActive) {
      if (this.winningOption !== null) {
        embed.setTitle(`【已結束】${this.topic}`);
        embed.addFields({ name: '獲勝選項', value: this.options[this.winningOption]?.label ?? '未知' });
      }
    } else if (this.endTime) {
      embed.addFields({ name: '下注截止時間', value: `<t:${Math.floor(this.endTime / 1000)}:R>` });
    }

    return embed;
  }

  static fromDbRow(row: any): Bet {
    // The constructor expects an array of strings (labels) for the options
    const optionsLabels = (JSON.parse(row.options) as BetOption[]).map(o => o.label);
    const bet = new Bet(row.channelId, row.topic, optionsLabels, row.endTime, row.id);

    // Now we can manually set the other properties
    bet.isActive = row.isActive === 1;
    bet.winningOption = row.winningOption;
    // The pointsPools will be calculated separately in getBet

    return bet;
  }
}

