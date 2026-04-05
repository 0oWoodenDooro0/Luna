# Implementation Plan: RPG UI Improvements

## Phase 1: Help Command and Basic Infrastructure [checkpoint: 244cfd6]
- [x] Task: Write Tests for `HelpCommand` eef8571
    - [x] Create `HelpCommandTest.kt`
    - [x] Verify that the command correctly lists all RPG commands and descriptions
- [x] Task: Implement `HelpCommand` eef8571
    - [x] Create `src/main/kotlin/luna/rpg/command/HelpCommand.kt`
    - [x] Register the command in `Main.kt`
    - [x] Implement logic to display a list of all RPG commands (Chinese)
- [x] Task: Conductor - User Manual Verification 'Phase 1' (Protocol in workflow.md) 244cfd6

## Phase 2: Upgrade and Rebirth List Commands
- [ ] Task: Write Tests for `UpgradeListCommand` and `RebirthListCommand`
    - [ ] Create `UpgradeListCommandTest.kt`
    - [ ] Create `RebirthListCommandTest.kt`
    - [ ] Verify that the displays correctly show current level, cost, and availability
- [ ] Task: Implement `UpgradeListCommand`
    - [ ] Create `src/main/kotlin/luna/rpg/command/UpgradeListCommand.kt`
    - [ ] Register the command in `Main.kt`
    - [ ] Implement Embed display showing all equipment upgrades (Weapon, Shield, Armor, Recovery)
- [ ] Task: Implement `RebirthListCommand`
    - [ ] Create `src/main/kotlin/luna/rpg/command/RebirthListCommand.kt`
    - [ ] Register the command in `Main.kt`
    - [ ] Implement Embed display showing all rebirth upgrades and current point usage
- [ ] Task: Conductor - User Manual Verification 'Phase 2' (Protocol in workflow.md)

## Phase 3: Enhanced Status and Command UI
- [ ] Task: Write Tests for updated Status and Upgrade commands
    - [ ] Update `StatusCommandTest.kt` or create a new test for enhanced info
    - [ ] Verify that resource totals and rebirth progress are correctly displayed
- [ ] Task: Update `StatusCommand`
    - [ ] Add display for current Wood, Stone, and Metal amounts
    - [ ] Add display for rebirth milestone progress (Floor level check)
- [ ] Task: Update `UpgradeCommand` and `RebirthUpgradeCommand`
    - [ ] Refactor success/failure messages to use Discord Embeds
    - [ ] Add "Next Level Stats" (e.g., ATK +5) information in success messages
- [ ] Task: Conductor - User Manual Verification 'Phase 3' (Protocol in workflow.md)

## Phase 4: Final Polishing and Verification
- [ ] Task: UI Polish and Consistency Check
    - [ ] Review all command displays for consistent use of emojis and headers
    - [ ] Ensure all Chinese translations are natural and user-friendly
- [ ] Task: Conductor - User Manual Verification 'Phase 4' (Protocol in workflow.md)
