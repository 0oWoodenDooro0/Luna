package luna.rpg.command

import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import dev.kord.rest.builder.interaction.integer
import dev.kord.rest.builder.interaction.number
import dev.kord.rest.builder.interaction.subCommand
import dev.kord.rest.builder.message.embed
import luna.core.Command
import luna.rpg.MapService
import luna.rpg.RpgConfig
import luna.rpg.repository.PlayerMapRepository
import luna.rpg.repository.PlayerRepository

class MapCommand : Command {
    override val name = "map"
    override val description = "地圖系統：創建、列出、選擇或刪除你的地圖"

    override suspend fun register(kord: Kord) {
        kord.createGlobalChatInputCommand(name, description) {
            subCommand("create", "創建一個自定義地圖") {
                integer("layer", "地圖的樓層/難度") {
                    required = true
                    minValue = 1
                }
                number("drop_rate", "資源掉落率倍率 (0.6 ~ 1.5)") {
                    required = true
                    minValue = 0.6
                    maxValue = 1.5
                }
            }
            subCommand("list", "列出你擁有的所有地圖")
            subCommand("select", "選擇一個地圖作為目前的探索地圖") {
                integer("id", "地圖的 ID") {
                    required = true
                }
            }
            subCommand("delete", "刪除一個地圖") {
                integer("id", "地圖的 ID") {
                    required = true
                }
            }
        }
    }

    override suspend fun handle(interaction: ChatInputCommandInteraction) {
        val userId = interaction.user.id.toString()
        
        // Kord subcommands are accessed via interaction.command
        val command = interaction.command
        
        when (command.rootName) {
            "map" -> {
                // Determine which subcommand was used
                // In Kord, we can check if a subcommand group or subcommand is present
                // Since we used subCommand directly under root:
                when {
                    interaction.command.integers.containsKey("layer") || interaction.command.numbers.containsKey("drop_rate") -> {
                         // This is a bit ambiguous if options overlap, but here they don't
                         // However, the best way is to check the subcommand name from data
                    }
                }
                
                // Let's use a safer approach by checking data.options
                val subCommandName = interaction.data.data.options.value?.firstOrNull()?.name
                
                when (subCommandName) {
                    "create" -> handleCreate(interaction, userId)
                    "list" -> handleList(interaction, userId)
                    "select" -> handleSelect(interaction, userId)
                    "delete" -> handleDelete(interaction, userId)
                    else -> {
                        interaction.deferPublicResponse().respond { content = "未知指令。" }
                    }
                }
            }
        }
    }

    private suspend fun handleCreate(interaction: ChatInputCommandInteraction, userId: String) {
        val layer = interaction.command.integers["layer"]?.toInt() ?: 1
        val dropRate = interaction.command.numbers["drop_rate"] ?: 1.0

        val response = interaction.deferPublicResponse()
        val result = MapService.createMap(userId, layer, dropRate)

        when (result) {
            is MapService.CreateMapResult.Success -> {
                val (wood, stone, metal) = RpgConfig.Map.calculateCost(layer, dropRate)
                response.respond {
                    embed {
                        title = "✅ 地圖創建成功！"
                        description = "你成功創建了一張地圖！"
                        color = dev.kord.common.Color(0x2ECC71)
                        field {
                            name = "地圖屬性"
                            value = "樓層: $layer\n掉落率: ${dropRate}x\n房間數: 20"
                        }
                        field {
                            name = "消耗資源"
                            value = "🪵 木頭: $wood\n🪨 石頭: $stone\n🔗 金屬: $metal"
                        }
                        footer {
                            text = "地圖 ID: ${result.mapId}"
                        }
                    }
                }
            }
            is MapService.CreateMapResult.InsufficientResources -> {
                val missingStr = result.missing.joinToString("\n") { 
                    "${it.name}: 需要 ${it.required}, 現有 ${it.current}" 
                }
                response.respond {
                    embed {
                        title = "❌ 資源不足"
                        description = "你沒有足夠的資源來創建這張地圖：\n$missingStr"
                        color = dev.kord.common.Color(0xE74C3C)
                    }
                }
            }
            MapService.CreateMapResult.InvalidParameters -> {
                response.respond {
                    content = "❌ 無效的參數。掉落率必須在 0.6 到 1.5 之間。"
                }
            }
        }
    }

    private suspend fun handleList(interaction: ChatInputCommandInteraction, userId: String) {
        val response = interaction.deferPublicResponse()
        val maps = PlayerMapRepository.getMaps(userId)

        if (maps.isEmpty()) {
            response.respond {
                content = "你目前沒有任何地圖。使用 `/map create` 來創建一個！"
            }
            return
        }

        response.respond {
            embed {
                title = "🗺️ 你的地圖庫"
                description = "以下是你擁有的所有地圖："
                color = dev.kord.common.Color(0x3498DB)
                
                maps.forEach { map ->
                    field {
                        name = "ID: ${map.id} ${if (map.isActive) " (目前選中 ✅)" else ""}"
                        value = "樓層: ${map.layer} | 掉落率: ${map.dropRate}x | 進度: ${map.currentRoom}/20"
                        inline = false
                    }
                }
                
                footer {
                    text = "使用 /map select <id> 來切換地圖"
                }
            }
        }
    }

    private suspend fun handleSelect(interaction: ChatInputCommandInteraction, userId: String) {
        val mapId = interaction.command.integers["id"]?.toInt() ?: -1
        
        val response = interaction.deferPublicResponse()
        val maps = PlayerMapRepository.getMaps(userId)
        val targetMap = maps.find { it.id == mapId }

        if (targetMap == null) {
            response.respond {
                content = "❌ 找不到 ID 為 $mapId 的地圖。"
            }
            return
        }

        PlayerMapRepository.setActiveMap(userId, mapId)
        response.respond {
            content = "✅ 已將地圖 ID $mapId (第 ${targetMap.layer} 層) 設為目前的探索地圖。"
        }
    }

    private suspend fun handleDelete(interaction: ChatInputCommandInteraction, userId: String) {
        val mapId = interaction.command.integers["id"]?.toInt() ?: -1
        
        val response = interaction.deferPublicResponse()
        val maps = PlayerMapRepository.getMaps(userId)
        val targetMap = maps.find { it.id == mapId }

        if (targetMap == null) {
            response.respond {
                content = "❌ 找不到 ID 為 $mapId 的地圖。"
            }
            return
        }

        PlayerMapRepository.deleteMap(userId, mapId)
        response.respond {
            content = "🗑️ 已刪除地圖 ID $mapId。"
        }
    }
}
