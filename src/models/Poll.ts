import { EmbedBuilder, ActionRowBuilder, ButtonBuilder, ButtonStyle } from 'discord.js';

interface PollOption {
  label: string;
  votes: number;
}

// This interface defines the simple object structure for saving to JSON
export interface PollJSON {
  id: string;
  topic: string;
  options: PollOption[];
  userVotes: [string, number][]; // Map converted to an array of [key, value] pairs
}

export class Poll {
  public id: string; // The Discord message ID
  public topic: string;
  public options: PollOption[];
  private userVotes: Map<string, number>;

  constructor(topic: string, options: string[], id: string = '') {
    this.id = id;
    this.topic = topic;
    this.options = options.map(opt => ({ label: opt, votes: 0 }));
    this.userVotes = new Map();
  }

  addVote(userId: string, optionIndex: number) {
    // Check if the provided vote index is valid for our options array.
    const selectedOption = this.options[optionIndex];
    if (!selectedOption) {
      console.error(`Attempted to vote for an invalid option index: ${optionIndex}`);
      return; // If the option doesn't exist, stop the function here.
    }

    // If the user has already voted, find their previous vote and decrement it.
    if (this.userVotes.has(userId)) {
      const previousVoteIndex = this.userVotes.get(userId)!;
      const previousOption = this.options[previousVoteIndex];
      if (previousOption) {
        previousOption.votes--;
      }
    }

    selectedOption.votes++;
    this.userVotes.set(userId, optionIndex);
  }

  // --- Persistence Methods ---

  // Converts the Poll object into a simple JSON object for saving
  toJSON(): PollJSON {
    return {
      id: this.id,
      topic: this.topic,
      options: this.options,
      userVotes: Array.from(this.userVotes.entries()),
    };
  }

  // Creates a Poll instance from a simple JSON object
  static fromJSON(data: PollJSON): Poll {
    const poll = new Poll(data.topic, [], data.id);
    poll.options = data.options;
    poll.userVotes = new Map(data.userVotes);
    return poll;
  }

  // --- Display Methods ---

  createMessagePayload() {
    const embed = new EmbedBuilder()
      .setColor(0x0099FF)
      .setTitle(`投票主題：${this.topic}`)
      .setDescription(this.generateVoteCountString())
      .setFooter({ text: `Poll ID: ${this.id}` }); // Add the ID to the footer

    const row = new ActionRowBuilder<ButtonBuilder>();
    this.options.forEach((option, index) => {
      row.addComponents(
        new ButtonBuilder()
          .setCustomId(`vote-${this.id}-${index}`) // Make the button ID unique to the poll
          .setLabel(option.label)
          .setStyle(ButtonStyle.Primary)
      );
    });

    return { embeds: [embed], components: [row] };
  }

  private generateVoteCountString(): string {
    let description = '點擊按鈕或使用 `/vote cast` 指令來投票！\n\n**目前結果:**\n';
    this.options.forEach(opt => {
      description += `**${opt.label}**: ${opt.votes} 票\n`;
    });
    return description;
  }
}
