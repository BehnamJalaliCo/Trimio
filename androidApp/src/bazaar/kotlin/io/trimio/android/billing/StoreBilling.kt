package io.trimio.android.billing

import android.content.Context
import io.trimio.android.BuildConfig
import io.trimio.core.data.PurchaseResult
import io.trimio.core.data.StoreProduct
import ir.cafebazaar.poolakey.Connection
import ir.cafebazaar.poolakey.Payment
import ir.cafebazaar.poolakey.config.PaymentConfiguration
import ir.cafebazaar.poolakey.config.SecurityCheck
import ir.cafebazaar.poolakey.entity.PurchaseState
import ir.cafebazaar.poolakey.request.PurchaseRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

/**
 * Cafe Bazaar billing through Poolakey. Purchases are checked locally against the app's Bazaar
 * RSA key; builds without the key ([BuildConfig.BAZAAR_RSA_KEY] empty) have no store.
 */
class StoreBilling(context: Context, @Suppress("unused") private val scope: CoroutineScope) : ActivityBilling() {
    private val _owned = MutableStateFlow(emptySet<String>())
    override val owned: StateFlow<Set<String>> = _owned.asStateFlow()

    private val enabled = BuildConfig.BAZAAR_RSA_KEY.isNotBlank()
    private val payment = Payment(
        context = context.applicationContext,
        config = PaymentConfiguration(localSecurityCheck = SecurityCheck.Enable(rsaPublicKey = BuildConfig.BAZAAR_RSA_KEY)),
    )
    private var connection: Connection? = null
    private val connectLock = Mutex()

    override suspend fun product(id: String): StoreProduct? {
        if (!connect()) return null
        return suspendCancellableCoroutine { cont ->
            payment.getInAppSkuDetails(skuIds = listOf(id)) {
                getSkuDetailsSucceed { list -> cont.resume(list.firstOrNull()?.let { StoreProduct(it.sku, it.title, it.price) }) }
                getSkuDetailsFailed { cont.resume(null) }
            }
        }
    }

    override suspend fun refresh() {
        if (!connect()) return
        val ids = suspendCancellableCoroutine { cont ->
            payment.getPurchasedProducts {
                querySucceed { list -> cont.resume(list.filter { it.purchaseState == PurchaseState.PURCHASED }.map { it.productId }.toSet()) }
                queryFailed { cont.resume(null) }
            }
        }
        if (ids != null) _owned.value = ids
    }

    override suspend fun purchase(id: String): PurchaseResult {
        val host = activity ?: return PurchaseResult.Failed("No activity")
        if (!connect()) return PurchaseResult.Failed("Bazaar is not available")
        return suspendCancellableCoroutine { cont ->
            payment.purchaseProduct(registry = host.activityResultRegistry, request = PurchaseRequest(productId = id)) {
                failedToBeginFlow { cont.resume(PurchaseResult.Failed(it.message.orEmpty())) }
                purchaseSucceed { purchase ->
                    _owned.update { owned -> owned + purchase.productId }
                    cont.resume(PurchaseResult.Purchased)
                }
                purchaseCanceled { cont.resume(PurchaseResult.Cancelled) }
                purchaseFailed { cont.resume(PurchaseResult.Failed(it.message.orEmpty())) }
            }
        }
    }

    private suspend fun connect(): Boolean = connectLock.withLock {
        if (!enabled) return@withLock false
        if (connection?.getState() is ir.cafebazaar.poolakey.ConnectionState.Connected) return@withLock true
        suspendCancellableCoroutine { cont ->
            connection = payment.connect {
                connectionSucceed { if (cont.isActive) cont.resume(true) }
                connectionFailed { if (cont.isActive) cont.resume(false) }
                disconnected { if (cont.isActive) cont.resume(false) }
            }
        }
    }
}
