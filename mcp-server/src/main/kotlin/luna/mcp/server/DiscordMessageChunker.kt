package luna.mcp.server

object DiscordMessageChunker {
    const val MAX_DISCORD_MESSAGE_LENGTH = 2000

    /**
     * Splits an outgoing message into sequential chunks within Discord's 2,000-character limit,
     * preserving paragraph and line break boundaries (`\n`) wherever possible.
     */
    fun chunk(
        message: String,
        maxChunkSize: Int = MAX_DISCORD_MESSAGE_LENGTH,
    ): List<String> {
        if (message.length <= maxChunkSize) {
            return listOf(message)
        }

        val chunks = mutableListOf<String>()
        val lines = message.split("\n")
        val currentChunk = StringBuilder()

        for (line in lines) {
            val separator = if (currentChunk.isEmpty()) "" else "\n"
            val additionalLength = separator.length + line.length

            if (currentChunk.length + additionalLength <= maxChunkSize) {
                currentChunk.append(separator).append(line)
            } else {
                if (currentChunk.isNotEmpty()) {
                    chunks.add(currentChunk.toString())
                    currentChunk.clear()
                }

                if (line.length <= maxChunkSize) {
                    currentChunk.append(line)
                } else {
                    var remaining = line
                    while (remaining.length > maxChunkSize) {
                        chunks.add(remaining.substring(0, maxChunkSize))
                        remaining = remaining.substring(maxChunkSize)
                    }
                    if (remaining.isNotEmpty()) {
                        currentChunk.append(remaining)
                    }
                }
            }
        }

        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk.toString())
        }

        return chunks
    }
}
