package com.planboq.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.android.billingclient.api.ProductDetails

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaywallScreen(nav: NavController) {
    val app = LocalApp.current
    val activity = LocalContext.current as? android.app.Activity
    val state by app.billingManager.state.collectAsState()

    fun priceOf(pd: ProductDetails): String =
        pd.subscriptionOfferDetails?.firstOrNull()
            ?.pricingPhases?.pricingPhaseList?.firstOrNull()?.formattedPrice
            ?: ""

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PlanBOQ Pro") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Unlimited conversions, clean exports, saved projects.",
                style = MaterialTheme.typography.bodyLarge,
            )

            if (state.entitled) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("You are a Pro subscriber. Thank you!", style = MaterialTheme.typography.titleMedium)
                    }
                }
            } else {
                state.products.forEach { pd ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                if (pd.productId == com.planboq.app.data.billing.BillingManager.PRODUCT_YEARLY) "Yearly" else "Monthly",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(priceOf(pd), style = MaterialTheme.typography.headlineSmall)
                            Button(
                                onClick = {
                                    if (activity != null) app.billingManager.launchPurchase(activity, pd)
                                },
                                enabled = state.connected,
                                modifier = Modifier.padding(top = 8.dp),
                            ) { Text("Subscribe") }
                        }
                    }
                }
                if (state.products.isEmpty()) {
                    Text(
                        state.message ?: "Loading subscription options… (needs Google Play / signed-in account)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                "Free plan: ${com.planboq.app.data.billing.FreeTier.MONTHLY_LIMIT} conversions per month, watermarked exports.\n" +
                    "Pro: unlimited conversions, no watermark, priority server processing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
