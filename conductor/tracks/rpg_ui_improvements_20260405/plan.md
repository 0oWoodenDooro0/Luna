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

## Phase 2: Upgrade and Rebirth List Commands [checkpoint: 058b323]
- [x] Task: Write Tests for `UpgradeListCommand` and `RebirthListCommand` 5cea90a
    - [x] Create `UpgradeListCommandTest.kt`
    - [x] Create `RebirthListCommandTest.kt`
    - [x] Verify that the displays correctly show current level, cost, and availability
- [x] Task: Implement `UpgradeListCommand` 5cea90a
    - [x] Create `src/main/kotlin/luna/rpg/command/UpgradeListCommand.kt`
    - [x] Register the command in `Main.kt`
    - [x] Implement Embed display showing all equipment upgrades (Weapon, Shield, Armor, Recovery)
- [x] Task: Implement `RebirthListCommand` 5cea90a
    - [x] Create `src/main/kotlin/luna/rpg/command/RebirthListCommand.kt`
    - [x] Register the command in `Main.kt`
    - [x] Implement Embed display showing all rebirth upgrades and current point usage
- [x] Task: Conductor - User Manual Verification 'Phase 2' (Protocol in workflow.md) 058b323

## Phase 3: Enhanced Status and Command UI [checkpoint: 49d3d2a]
- [x] Task: Write Tests for updated Status and Upgrade commands ae0e344/c343cb9
    - [x] Update `StatusCommandTest.kt` or create a new test for enhanced info
    - [x] Verify that resource totals and rebirth progress are correctly displayed
- [x] Task: Update `StatusCommand` ae0e344
    - [x] Add display for current Wood, Stone, and Metal amounts
    - [x] Add display for rebirth milestone progress (Floor level check)
- [x] Task: Update `UpgradeCommand` and `RebirthUpgradeCommand` ae0e344/c343cb9
    - [x] Refactor success/failure messages to use Discord Embeds
    - [x] Add "Next Level Stats" (e.g., ATK +5) information in success messages
- [x] Task: Conductor - User Manual Verification 'Phase 3' (Protocol in workflow.md) 49d3d2a

## Phase 4: Final Polishing and Verification
- [ ] Task: UI Polish and Consistency Check
    - [ ] Review all command displays for consistent use of emojis and headers
    - [ ] Ensure all Chinese translations are natural and user-friendly
- [ ] Task: Conductor - User Manual Verification 'Phase 4' (Protocol in workflow.md)
