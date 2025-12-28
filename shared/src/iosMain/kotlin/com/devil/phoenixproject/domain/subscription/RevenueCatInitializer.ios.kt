package com.devil.phoenixproject.domain.subscription

import com.devil.phoenixproject.config.PlatformConfig
import com.revenuecat.purchases.kmp.Purchases
import com.revenuecat.purchases.kmp.PurchasesConfiguration

actual object RevenueCatInitializer {
    actual fun initialize() {
        Purchases.configure(
            PurchasesConfiguration(
                apiKey = PlatformConfig.revenueCatApiKey
            )
        )
    }
}
