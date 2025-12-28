package com.devil.phoenixproject.domain.subscription

import android.app.Application
import com.devil.phoenixproject.config.PlatformConfig
import com.revenuecat.purchases.kmp.Purchases
import com.revenuecat.purchases.kmp.PurchasesConfiguration

actual object RevenueCatInitializer {
    private var application: Application? = null

    fun setApplication(app: Application) {
        application = app
    }

    actual fun initialize() {
        val app = application ?: throw IllegalStateException(
            "Application not set. Call setApplication() first."
        )

        Purchases.configure(
            PurchasesConfiguration(
                apiKey = PlatformConfig.revenueCatApiKey
            )
        )
    }
}
