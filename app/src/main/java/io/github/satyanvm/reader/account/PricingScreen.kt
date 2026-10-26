package io.github.satyanvm.reader.account

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** One plan on the pricing page. [key] is the server's plan name; null for Free. */
private data class Plan(
    val key: String?,
    val name: String,
    val price: String,
    val period: String,
    val blurb: String,
    val features: List<String>,
    val highlighted: Boolean = false,
)

// Prices are shown here as text; what's charged is set on the Lemon Squeezy products (keep them equal).
private val PLANS = listOf(
    Plan(
        key = null,
        name = "Free",
        price = "$0",
        period = "forever",
        blurb = "Everything you need to read on this phone.",
        features = listOf(
            "PDFs and EPUBs",
            "Flowing text, page by page",
            "Light, sepia and dark themes",
            "Currently reading list and wishlist",
        ),
    ),
    Plan(
        key = "monthly",
        name = "Pro",
        price = "$2.99",
        period = "per month",
        blurb = "Your library on every device.",
        features = listOf(
            "Everything in Free",
            "Sync books and your place across devices",
            "Text from scanned PDFs",
            "Priority support",
        ),
        highlighted = true,
    ),
    Plan(
        key = "yearly",
        name = "Pro yearly",
        price = "$24.99",
        period = "per year",
        blurb = "Pro, with about two months free.",
        features = listOf("Everything in Pro", "Billed once a year"),
    ),
)

/** How the account's plan looks on this page. */
private sealed interface PlanState {
    data object Loading : PlanState
    data object SignedOut : PlanState
    data class Known(val billing: AccountApi.Billing) : PlanState
}

/**
 * The plans, and buying one: checkout happens on Lemon Squeezy's page in the browser, which
 * tells the sync server when it's paid. Coming back here refreshes the plan.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PricingScreen(account: SyncAccount, onSignIn: () -> Unit, onBack: () -> Unit) {
    val stage by account.stage.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<PlanState>(PlanState.Loading) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var resumes by remember { mutableIntStateOf(0) }
    var checkingOut by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose {}
    }
    LaunchedEffect(stage, resumes) {
        try {
            val billing = account.billing()
            state = billing?.let { PlanState.Known(it) } ?: PlanState.SignedOut
            error = null
            // Back from checkout: the payment reaches the server a few seconds after the page says paid.
            if (checkingOut && billing != null && !billing.pro) {
                for (attempt in 1..10) {
                    delay(3_000)
                    val next = account.billing() ?: break
                    state = PlanState.Known(next)
                    if (next.pro) break
                }
            }
            checkingOut = false
        } catch (e: AccountException) {
            if (state == PlanState.Loading) state = PlanState.SignedOut
            error = e.message
        }
    }

    fun buy(plan: String) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                val url = account.checkoutUrl(plan)
                checkingOut = true
                uriHandler.openUri(url)
            } catch (e: AccountException) {
                error = e.message
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Couldn't open the checkout page: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pricing") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val billing = (state as? PlanState.Known)?.billing
            if (billing?.pro == true) {
                ProBanner(billing, onManage = { billing.manageUrl?.let(uriHandler::openUri) })
            } else {
                Text("Choose how you read", style = MaterialTheme.typography.headlineSmall)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            for (plan in PLANS) {
                PlanCard(plan) {
                    when {
                        plan.key == null -> if (billing?.pro != true) {
                            OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("Your plan") }
                        }
                        billing?.pro == true -> Unit
                        state == PlanState.SignedOut -> Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
                            Text("Sign in to upgrade")
                        }
                        billing != null && plan.key in billing.plans -> Button(
                            onClick = { buy(plan.key) },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Upgrade") }
                        else -> Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                            Text(if (state == PlanState.Loading) "…" else "Coming soon")
                        }
                    }
                }
            }
            Text(
                "Payments are processed by Lemon Squeezy, our reseller, in your local currency with tax included. " +
                    "Cancel any time; Pro stays until the end of the period you paid for.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProBanner(billing: AccountApi.Billing, onManage: () -> Unit) {
    val date = { ms: Long -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms)) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("You have Pro", style = MaterialTheme.typography.headlineSmall)
        val detail = when {
            billing.endsAt != null -> "Cancelled; Pro lasts until ${date(billing.endsAt)}."
            billing.renewsAt != null -> "${billing.plan ?: "Pro"}, renews ${date(billing.renewsAt)}."
            else -> billing.plan
        }
        detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (billing.manageUrl != null) {
            OutlinedButton(onClick = onManage) { Text("Manage subscription") }
        }
    }
}

@Composable
private fun PlanCard(plan: Plan, action: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = if (plan.highlighted) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(plan.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (plan.highlighted) {
                    Text("Most popular", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(plan.price, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    " ${plan.period}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Text(plan.blurb, style = MaterialTheme.typography.bodyMedium)
            for (feature in plan.features) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(feature, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                }
            }
            action()
        }
    }
}
