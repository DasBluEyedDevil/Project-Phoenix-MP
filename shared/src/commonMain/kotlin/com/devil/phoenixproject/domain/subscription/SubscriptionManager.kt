package com.devil.phoenixproject.domain.subscription

import com.devil.phoenixproject.config.AppConfig
import com.devil.phoenixproject.data.repository.SubscriptionStatus
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.revenuecat.purchases.kmp.CustomerInfo
import com.revenuecat.purchases.kmp.Purchases
import com.revenuecat.purchases.kmp.PurchasesDelegate
import com.revenuecat.purchases.kmp.PurchasesError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class SubscriptionManager(
    private val userProfileRepository: UserProfileRepository
) : PurchasesDelegate {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _customerInfo = MutableStateFlow<CustomerInfo?>(null)
    val customerInfo: StateFlow<CustomerInfo?> = _customerInfo.asStateFlow()

    private val _isProSubscriber = MutableStateFlow(false)
    val hasProAccess: StateFlow<Boolean> = _isProSubscriber.asStateFlow()

    // Feature access flows - all tied to Pro for now
    val canUseAIRoutines: StateFlow<Boolean> = hasProAccess
    val canAccessCommunityLibrary: StateFlow<Boolean> = hasProAccess
    val canSyncToCloud: StateFlow<Boolean> = hasProAccess
    val canUseHealthIntegrations: StateFlow<Boolean> = hasProAccess

    init {
        // Watch local profile subscription status as backup/initial state
        scope.launch {
            userProfileRepository.getActiveProfileSubscriptionStatus().collect { status ->
                if (_customerInfo.value == null) {
                    // Use local status if RevenueCat hasn't loaded yet
                    _isProSubscriber.value = status == SubscriptionStatus.ACTIVE
                }
            }
        }
    }

    fun setupDelegate() {
        try {
            Purchases.sharedInstance.delegate = this
        } catch (e: Exception) {
            // RevenueCat not initialized yet
        }
    }

    override fun onCustomerInfoUpdated(customerInfo: CustomerInfo) {
        _customerInfo.value = customerInfo
        updateProStatus(customerInfo)
    }

    override fun onPurchasePromoProduct(
        product: com.revenuecat.purchases.kmp.models.StoreProduct,
        startPurchase: (onError: (error: PurchasesError, userCancelled: Boolean) -> Unit, onSuccess: (storeTransaction: com.revenuecat.purchases.kmp.models.StoreTransaction, customerInfo: CustomerInfo) -> Unit) -> Unit
    ) {
        // Handle promotional purchases if needed
    }

    private fun updateProStatus(customerInfo: CustomerInfo) {
        val hasEntitlement = customerInfo.entitlements
            .active
            .containsKey(AppConfig.RevenueCat.ENTITLEMENT_PRO)
        _isProSubscriber.value = hasEntitlement

        // Sync to local database
        scope.launch {
            userProfileRepository.activeProfile.value?.let { profile ->
                val status = if (hasEntitlement) SubscriptionStatus.ACTIVE else SubscriptionStatus.FREE
                val expiresAt = customerInfo.entitlements
                    .active[AppConfig.RevenueCat.ENTITLEMENT_PRO]
                    ?.expirationDateMillis
                userProfileRepository.updateSubscriptionStatus(profile.id, status, expiresAt)
            }
        }
    }

    suspend fun refreshCustomerInfo(): Result<CustomerInfo> = suspendCoroutine { cont ->
        try {
            Purchases.sharedInstance.getCustomerInfo(
                onError = { error ->
                    cont.resumeWithException(Exception(error.message))
                },
                onSuccess = { info ->
                    _customerInfo.value = info
                    updateProStatus(info)
                    cont.resume(Result.success(info))
                }
            )
        } catch (e: Exception) {
            cont.resume(Result.failure(e))
        }
    }

    suspend fun restorePurchases(): Result<CustomerInfo> = suspendCoroutine { cont ->
        try {
            Purchases.sharedInstance.restorePurchases(
                onError = { error ->
                    cont.resumeWithException(Exception(error.message))
                },
                onSuccess = { info ->
                    _customerInfo.value = info
                    updateProStatus(info)
                    cont.resume(Result.success(info))
                }
            )
        } catch (e: Exception) {
            cont.resume(Result.failure(e))
        }
    }

    fun loginToRevenueCat(userId: String) {
        scope.launch {
            try {
                Purchases.sharedInstance.logIn(
                    newAppUserID = userId,
                    onError = { /* Handle error silently */ },
                    onSuccess = { info, _ ->
                        _customerInfo.value = info
                        updateProStatus(info)
                    }
                )
            } catch (e: Exception) {
                // Handle error silently - will retry on next app launch
            }
        }
    }

    fun logoutFromRevenueCat() {
        scope.launch {
            try {
                Purchases.sharedInstance.logOut(
                    onError = { /* Handle error silently */ },
                    onSuccess = { info ->
                        _customerInfo.value = info
                        _isProSubscriber.value = false
                    }
                )
            } catch (e: Exception) {
                // Handle error silently
            }
        }
    }
}
