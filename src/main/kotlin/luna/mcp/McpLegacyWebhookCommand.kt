package luna.mcp

import dev.kord.common.entity.Permission
import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import dev.kord.core.entity.interaction.SubCommand
import dev.kord.rest.builder.interaction.string
import dev.kord.rest.builder.interaction.subCommand
import luna.core.Command

class McpLegacyWebhookCommand(
    private val storage: McpWebhookStorage,
) : Command {
    override val name: String = "mcp-webhook"
    override val description: String = "Manage Discord webhooks for MCP integration"

    override suspend fun register(kord: Kord) {
        kord.createGlobalChatInputCommand(name, description) {
            subCommand("add", "Add a Discord webhook URL for this channel") {
                string("webhook_url", "Discord Webhook URL to add") {
                    required = true
                }
            }
            subCommand("remove", "Remove Discord webhook(s) for this channel") {
                string("webhook_url", "Discord Webhook URL to remove (omit to remove all for channel)") {
                    required = false
                }
            }
        }
    }

    override suspend fun handle(interaction: ChatInputCommandInteraction) {
        val rawGuildId = interaction.data.guildId.value
        if (rawGuildId == null) {
            val response = interaction.deferEphemeralResponse()
            response.respond {
                content = "This command can only be used in a server."
            }
            return
        }

        val guildId = rawGuildId.toString()
        val member = interaction.user.asMemberOrNull(rawGuildId)
        val permissions = member?.getPermissions()
        val isManager =
            permissions?.contains(Permission.ManageGuild) == true ||
                permissions?.contains(Permission.Administrator) == true

        val response = interaction.deferEphemeralResponse()

        if (!isManager) {
            response.respond {
                content = "You must have Manage Server permission to manage MCP webhooks."
            }
            return
        }

        val subCommand = interaction.command as? SubCommand
        val channelId = interaction.channelId.toString()

        when (subCommand?.name) {
            "add" -> {
                val webhookUrl = subCommand.strings["webhook_url"]?.trim().orEmpty()
                if (!isValidWebhookUrl(webhookUrl)) {
                    response.respond {
                        content =
                            "Invalid Discord webhook URL. Expected format: " +
                            "https://discord.com/api/webhooks/<id>/<token> or " +
                            "https://canary.discord.com/api/webhooks/<id>/<token>"
                    }
                    return
                }

                storage.addWebhook(guildId, channelId, webhookUrl)
                response.respond {
                    content = "Successfully registered MCP webhook for channel <#$channelId>."
                }
            }

            "remove" -> {
                val webhookUrl = subCommand.strings["webhook_url"]?.trim()?.ifBlank { null }
                if (webhookUrl != null && !isValidWebhookUrl(webhookUrl)) {
                    response.respond {
                        content =
                            "Invalid Discord webhook URL. Expected format: " +
                            "https://discord.com/api/webhooks/<id>/<token> or " +
                            "https://canary.discord.com/api/webhooks/<id>/<token>"
                    }
                    return
                }

                val removed = storage.removeWebhook(guildId, channelId, webhookUrl)
                if (removed) {
                    if (webhookUrl != null) {
                        response.respond {
                            content = "Successfully removed webhook from channel <#$channelId>."
                        }
                    } else {
                        response.respond {
                            content = "Successfully removed all webhooks from channel <#$channelId>."
                        }
                    }
                } else {
                    if (webhookUrl != null) {
                        response.respond {
                            content = "No matching webhook found for channel <#$channelId>."
                        }
                    } else {
                        response.respond {
                            content = "No webhooks registered for channel <#$channelId>."
                        }
                    }
                }
            }

            else -> {
                response.respond {
                    content = "Unknown subcommand. Please use `/mcp-webhook add` or `/mcp-webhook remove`."
                }
            }
        }
    }

    companion object {
        val WEBHOOK_URL_REGEX = Regex("""^https://(canary\.)?discord\.com/api/webhooks/\d+/[\w-]+$""")

        fun isValidWebhookUrl(url: String): Boolean = WEBHOOK_URL_REGEX.matches(url)
    }
}
