# Track Specification: RPG UI Improvements

## Overview
This track aims to improve the user experience of the RPG system by providing clearer information about commands, upgrades, and progression. It introduces a `/help` command, an upgrade list display for both regular and rebirth upgrades, and enhanced status/upgrade screens with more detailed statistics.

## Functional Requirements
1.  **New Command: `/help`**
    *   Lists all RPG-related commands and their descriptions.
    *   Commands to include: `/explore`, `/status`, `/upgrade`, `/rebirth`, `/rebirth_upgrade`, `/settings`, `/help`.
2.  **New Command: `/upgrade_list`**
    *   Displays a full list of available equipment upgrades.
    *   For each upgrade, show:
        *   Current Level
        *   Resource requirements for the next level (Wood, Stone, Metal).
        *   Effect of the next level (e.g., +5 ATK).
        *   Availability status (Mark if resources are sufficient).
    *   Use a Discord Embed for a structured layout.
3.  **New Command: `/rebirth_list`**
    *   Displays a full list of available rebirth upgrades.
    *   For each upgrade, show:
        *   Current Level (up to Max Level 10).
        *   Rebirth point cost for the next level.
        *   Effect of the next level (e.g., +5% ATK).
        *   Availability status (Mark if points are sufficient).
    *   Use a Discord Embed for a structured layout.
4.  **Enhanced Command Displays**
    *   **`/status`**: Include current Resource totals (Wood, Stone, Metal) and progress towards the next rebirth milestone (current floor vs. minimum required floor).
    *   **`/upgrade` and `/rebirth_upgrade`**: Use Embeds for better formatting. Show "Next Level Stats" (exact stat increases) in the success message.
5.  **General UI improvements**
    *   Use emojis and clear headers to improve readability.

## Non-Functional Requirements
*   **Performance**: UI updates should be snappy and not introduce significant latency.
*   **Consistency**: Follow the existing code style and Discord interaction patterns.

## Acceptance Criteria
*   Players can use `/help` to see all RPG commands.
*   Players can view a comprehensive list of equipment and rebirth upgrades with their costs and effects.
*   The status screen clearly shows resource totals and rebirth eligibility.
*   The upgrade and rebirth upgrade commands use clear, formatted Embeds.
*   All new UI elements are consistent with the current Chinese interface.

## Out of Scope
*   Multi-player features or interactions.
*   New gameplay mechanics (only UI/info improvements).
*   Non-RPG command help (as requested by user).
