package io.trimio.core.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** A store product as the store shows it; [price] is already formatted in the user's currency. */
data class StoreProduct(val id: String, val title: String, val price: String)

sealed interface PurchaseResult {
    data object Purchased : PurchaseResult
    data object Pending : PurchaseResult
    data object Cancelled : PurchaseResult
    data class Failed(val reason: String) : PurchaseResult
}

/**
 * The app store's billing: Google Play in the `play` build, Cafe Bazaar (Poolakey) in `bazaar`,
 * none on web/desktop. Prices and products live in the store consoles, never in the app.
 */
interface Billing {
    /** Product ids the user owns (acknowledged purchases only). */
    val owned: StateFlow<Set<String>>

    /** Null when the store is unreachable or the product does not exist. */
    suspend fun product(id: String): StoreProduct?

    suspend fun refresh()

    suspend fun purchase(id: String): PurchaseResult
}

object NoBilling : Billing {
    private val none = MutableStateFlow(emptySet<String>())
    override val owned: StateFlow<Set<String>> = none.asStateFlow()
    override suspend fun product(id: String): StoreProduct? = null
    override suspend fun refresh() = Unit
    override suspend fun purchase(id: String): PurchaseResult = PurchaseResult.Failed("No store on this platform")
}

/**
 * Whether the paid tier is unlocked. The paywall is off until remote config names a product
 * ([paywallProduct] emits null meanwhile), and while it is off everyone has everything: no feature
 * is ever taken away by an update, only by a server switch the owner turns on deliberately.
 */
class Entitlements(val billing: Billing, paywallProduct: Flow<String?>, scope: CoroutineScope) {
    /** The product to sell, or null while the paywall is off. */
    val product: StateFlow<String?> = paywallProduct.stateIn(scope, SharingStarted.Eagerly, null)

    val pro: StateFlow<Boolean> = combine(product, billing.owned) { id, owned -> id == null || id in owned }
        .stateIn(scope, SharingStarted.Eagerly, true)
}
