package dev.socialpeek.exception

sealed class SocialPeekException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class UnsupportedPlatformException(url: String) : 
    SocialPeekException("No resolver found for URL: $url")

class PostNotFoundException(url: String, reason: String? = null) : 
    SocialPeekException("Post not found at $url${reason?.let { ": $it" } ?: ""}")

class RateLimitedException(url: String, platform: String) : 
    SocialPeekException("Rate limit reached for $platform while resolving $url")

class ParsingException(url: String, details: String, cause: Throwable? = null) : 
    SocialPeekException("Failed to parse content from $url: $details", cause)
