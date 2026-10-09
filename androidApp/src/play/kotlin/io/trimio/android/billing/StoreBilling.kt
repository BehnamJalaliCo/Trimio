package io.trimio.android.billing

import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryPurchasesAsync
import io.trimio.core.data.PurchaseResult
import io.trimio.core.data.StoreProduct
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

/** Google Play Billing: one-time products, acknowledged on the device after a verified purchase. */
class StoreBilling(context: Context, private val scope: CoroutineScope) : ActivityBilling() {
    private val _owned = MutableStateFlow(emptySet<String>())
    override val owned: StateFlow<Set<String>> = _owned.asStateFlow()

    private var pending: CompletableDeferred<PurchaseResult>? = null
    private val connectLock = Mutex()

    private val client = BillingClient.newBuilder(context.applicationContext)
        .setListener { result, purchases ->
            scope.launch {
                val outcome = when (result.responseCode) {
                    BillingClient.BillingResponseCode.OK -> {
                        handle(purchases.orEmpty())
                        if (purchases.orEmpty().any { it.purchaseState == Purchase.PurchaseState.PENDING }) PurchaseResult.Pending else PurchaseResult.Purchased
                    }
                    BillingClient.BillingResponseCode.USER_CANCELED -> PurchaseResult.Cancelled
                    BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                        refresh()
                        PurchaseResult.Purchased
                    }
                    else -> PurchaseResult.Failed(result.debugMessage)
                }
                pending?.complete(outcome)
            }
        }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    override suspend fun product(id: String): StoreProduct? = details(id)?.let {
        StoreProduct(it.productId, it.name, it.oneTimePurchaseOfferDetails?.formattedPrice ?: return null)
    }

    override suspend fun refresh() {
        if (!connect()) return
        val result = client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build())
        if (result.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            _owned.value = emptySet()
            handle(result.purchasesList)
        }
    }

    override suspend fun purchase(id: String): PurchaseResult {
        val host = activity ?: return PurchaseResult.Failed("No activity")
        val product = details(id) ?: return PurchaseResult.Failed("Product unavailable")
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product).build()))
            .build()
        val outcome = CompletableDeferred<PurchaseResult>().also { pending = it }
        val launch = client.launchBillingFlow(host, params)
        if (launch.responseCode != BillingClient.BillingResponseCode.OK) return PurchaseResult.Failed(launch.debugMessage)
        return outcome.await()
    }

    /** Grants owned products and acknowledges new ones (Play refunds unacknowledged purchases after 3 days). */
    private suspend fun handle(purchases: List<Purchase>) {
        for (purchase in purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }) {
            if (!purchase.isAcknowledged) {
                val ack = client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build())
                if (ack.responseCode != BillingClient.BillingResponseCode.OK) continue
            }
            _owned.update { it + purchase.products }
        }
    }

    private suspend fun details(id: String): ProductDetails? {
        if (!connect()) return null
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(QueryProductDetailsParams.Product.newBuilder().setProductId(id).setProductType(BillingClient.ProductType.INAPP).build()))
            .build()
        return suspendCancellableCoroutine { cont ->
            client.queryProductDetailsAsync(params) { result, details ->
                cont.resume(if (result.responseCode == BillingClient.BillingResponseCode.OK) details.productDetailsList.firstOrNull() else null)
            }
        }
    }

    private suspend fun connect(): Boolean = connectLock.withLock {
        if (client.isReady) return@withLock true
        suspendCancellableCoroutine { cont ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (cont.isActive) cont.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
                }

                override fun onBillingServiceDisconnected() {
                    if (cont.isActive) cont.resume(false)
                }
            })
        }
    }
}
