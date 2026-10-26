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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** One plan on the pricing page. */
private data class Plan(
    val name: String,
    val price: String,
    val period: String,
    val blurb: String,
    val features: List<String>,
    val current: Boolean = false,
    val highlighted: Boolean = false,
)

// Placeholder plans: nothing is billed yet. Edit names, prices and features here.
private val PLANS = listOf(
    Plan(
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
        current = true,
    ),
    Plan(
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
        name = "Pro yearly",
        price = "$24.99",
        period = "per year",
        blurb = "Pro, with about two months free.",
        features = listOf("Everything in Pro", "Billed once a year"),
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PricingScreen(onBack: () -> Unit) {
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
            Text("Choose how you read", style = MaterialTheme.typography.headlineSmall)
            for (plan in PLANS) PlanCard(plan)
            Text(
                "Paid plans are coming soon. You won't be charged for anything today.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PlanCard(plan: Plan) {
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
            if (plan.current) {
                OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("Your plan") }
            } else {
                Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("Coming soon") }
            }
        }
    }
}
