package com.devil.phoenixproject.domain.premium

/**
 * Subscription tier for feature access gating.
 *
 * This represents the feature access level, distinct from payment state
 * (which is handled by SubscriptionStatus in UserProfileRepository).
 *
 * Values stored in UserProfile.subscription_status as lowercase strings:
 * "free", "phoenix", "elite"
 */
enum class SubscriptionTier {
    FREE, PHOENIX, ELITE;

    companion object {
        fun fromDbString(value: String?): SubscriptionTier =
            when (value?.lowercase()) {
                "phoenix" -> PHOENIX
                "elite" -> ELITE
                else -> FREE
            }
    }

    fun toDbString(): String = name.lowercase()
}
