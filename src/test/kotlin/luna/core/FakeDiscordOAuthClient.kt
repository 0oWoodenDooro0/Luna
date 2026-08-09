package luna.core

class FakeDiscordOAuthClient(
    var mockAuthUrl: String = "https://discord.com/oauth2/authorize?mock=true",
    var mockUserProfile: DiscordUserProfile =
        DiscordUserProfile(
            id = "123456789012345678",
            username = "TestDiscordUser",
            globalName = "Discord Global Tester",
        ),
    var shouldFail: Boolean = false,
    var failureException: Exception = IllegalStateException("Mock Discord OAuth failure"),
) : DiscordOAuthClient {
    val receivedRedirectUris = mutableListOf<String>()
    val receivedCodes = mutableListOf<String>()

    override fun getAuthorizationUrl(redirectUri: String): String {
        receivedRedirectUris.add(redirectUri)
        return mockAuthUrl
    }

    override suspend fun exchangeCode(
        code: String,
        redirectUri: String,
    ): DiscordUserProfile {
        receivedCodes.add(code)
        receivedRedirectUris.add(redirectUri)
        if (shouldFail) {
            throw failureException
        }
        return mockUserProfile
    }
}
