# Track Specification: Implement Core RPG Stats and Exploration Mechanics

## Overview
This track introduces the foundational elements of the Discord Text RPG, building on the existing Kotlin and Kord framework. It covers the core player and monster attributes, basic data persistence with SQLite and Exposed, and the first exploration command.

## Objectives
- Implement `Player` and `Monster` data models with HP, ATK, DEF, and SPD.
- Set up SQLite tables for player stats and resources (Wood, Stone, Metal).
- Implement the `/status` command to display player stats.
- Implement the `/explore` command with basic random events (finding resources or encountering monsters).

## Requirements
- Attributes must match the "企劃書": 血量 (HP), 攻擊 (ATK), 防禦 (DEF), 速度 (SPD).
- Resource names: 🪵 木頭 (Wood), 🪨 石頭 (Stone), 🔗 金屬 (Metal).
- Combat in `/explore` must be automated and turn-based.
- Data must persist between Discord sessions.

## Constraints
- **Language:** Traditional Chinese (正體中文).
- **Tone:** Systemic & Concise.
- **UI:** Slash Commands and Rich Embeds.
- **Architecture:** Maintain parallel structure to the existing "Undercover" command logic.
