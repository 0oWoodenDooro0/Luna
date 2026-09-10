package luna.core

class AuthService(
    private val userStorage: UserStorage,
    private val discordOAuthClient: DiscordOAuthClient = HttpDiscordOAuthClient(),
) {
    fun getDiscordLoginUrl(redirectUri: String): String = discordOAuthClient.getAuthorizationUrl(redirectUri)

    suspend fun handleDiscordCallback(
        code: String,
        redirectUri: String,
    ): UserSession {
        val profile = discordOAuthClient.exchangeCode(code, redirectUri)
        val user =
            userStorage.upsertDiscordUser(
                discordUserId = profile.id,
                username = profile.displayName,
            )
        return UserSession(userId = user.id, username = user.username)
    }

    fun register(
        username: String,
        passwordRaw: String,
    ): UserSession {
        val user = userStorage.register(username, passwordRaw)
        return UserSession(userId = user.id, username = user.username)
    }

    fun authenticate(
        username: String,
        passwordRaw: String,
    ): UserSession? {
        val user = userStorage.authenticate(username, passwordRaw) ?: return null
        return UserSession(userId = user.id, username = user.username)
    }

    fun findById(id: String): User? = userStorage.findById(id)
}
