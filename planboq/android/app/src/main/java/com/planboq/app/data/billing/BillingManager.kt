package com.planboq.app.data.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryProductDetailsResult
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Play Billing wrapper (PBL 9). The app sells one auto-renewing subscription
 * with two offers (monthly / yearly). Without Google Play services (some
 * emulators) the client never connects and the app behaves as free tier.
 */
class BillingManager(private val context: Context) : PurchasesUpdatedListener {

    companion object {
        const val PRODUCT_MONTHLY = "planboq_pro_monthly"
        const val PRODUCT_YEARLY = "planboq_pro_yearly"
        val ALL_PRODUCTS = listOf(PRODUCT_MONTHLY, PRODUCT_YEARLY)
    }

    data class State(
        val connected: Boolean = false,
        val entitled: Boolean = false,
        val products: List<ProductDetails> = emptyList(),
        val message: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val client: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        // PBL 9: build() requires at least one pending-purchase product type;
        // this flag only governs one-time products, which we do not sell.
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build(),
        )
        .build()

    fun start() {
        if (client.isReady) {
            refresh()
            return
        }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                val ok = result.responseCode == BillingClient.BillingResponseCode.OK
                _state.value = _state.value.copy(
                    connected = ok,
                    message = if (ok) null else "Billing unavailable (${result.responseCode})",
                )
                if (ok) refresh()
            }

            override fun onBillingServiceDisconnected() {
                _state.value = _state.value.copy(connected = false)
            }
        })
    }

    fun refresh() {
        if (!client.isReady) return
        queryProducts()
        queryPurchases()
    }

    private fun queryProducts() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                ALL_PRODUCTS.map { id ->
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(id)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                },
            )
            .build()
        client.queryProductDetailsAsync(params) { result: BillingResult, detailsResult: QueryProductDetailsResult ->
            val products = detailsResult.productDetailsList ?: emptyList()
            _state.value = _state.value.copy(
                products = products,
                message = if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    "Could not load products (${result.responseCode})"
                } else {
                    null
                },
            )
        }
    }

    private fun queryPurchases() {
        client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build(),
        ) { result, purchases ->
            val active = if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                purchases.any { it.purchaseState == Purchase.PurchaseState.PURCHASED && it.products.intersect(ALL_PRODUCTS).isNotEmpty() }
            } else false
            _state.value = _state.value.copy(entitled = active)
            if (active) purchases.filter { !it.isAcknowledged }.forEach { ack(it.purchaseToken) }
        }
    }

    private fun ack(token: String) {
        client.acknowledgePurchase(
            AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build(),
        ) { /* best effort */ }
    }

    /** Launches the Play purchase sheet. Returns false if the flow could not start. */
    fun launchPurchase(activity: Activity, productDetails: ProductDetails): Boolean {
        if (!client.isReady) return false
        val offerToken = productDetails.subscriptionOfferDetails?.firstOrNull()?.offerToken
            ?: return false
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(productDetails)
                        .setOfferToken(offerToken)
                        .build(),
                ),
            )
            .build()
        return client.launchBillingFlow(activity, params).responseCode ==
            BillingClient.BillingResponseCode.OK
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            if (result.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) return
            _state.value = _state.value.copy(message = "Purchase failed (${result.responseCode})")
            return
        }
        val list = purchases ?: return
        val entitled = list.any {
            it.purchaseState == Purchase.PurchaseState.PURCHASED &&
                it.products.intersect(ALL_PRODUCTS).isNotEmpty()
        }
        if (entitled) {
            _state.value = _state.value.copy(entitled = true)
            list.filter { !it.isAcknowledged }.forEach { ack(it.purchaseToken) }
        }
    }
}
