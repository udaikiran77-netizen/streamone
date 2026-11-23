package com.streamone.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Tomato = Color(0xFFFF6347)
private val VegaBlack = Color(0xFF000000)
private val VegaSurface = Color(0xFF171717)
private val VegaText = Color(0xFFF2F2F2)
private val VegaSubText = Color(0xFFC4C4C4)

private data class ProviderInfo(
    val name: String,
    val kind: String,
    val status: String
)

private val providers = listOf(
    ProviderInfo("Cinejoy", "Vega provider", "Engine: Phase 2"),
    ProviderInfo("ThugTV", "Vega provider", "Engine: Phase 2"),
    ProviderInfo("DeadToons", "Vega provider", "Engine: Phase 2"),
    ProviderInfo("AethoFlix", "Vega provider", "Engine: Phase 2"),
    ProviderInfo("Zenda-Cross Providers", "Vega provider collection", "Engine: Phase 2"),
    ProviderInfo("CloudStream Extensions", "Extension system", "Engine: Phase 3")
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Tomato,
                    background = VegaBlack,
                    surface = VegaSurface,
                    onBackground = VegaText,
                    onSurface = VegaText
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = VegaBlack
                ) {
                    HomeScreen()
                }
            }
        }
    }
}

@Composable
private fun HomeScreen() {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(VegaBlack),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "StreamOne",
                color = Tomato,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "All your providers and extensions. One app.",
                color = VegaSubText,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "PROVIDERS",
                color = VegaText,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        items(providers) { provider ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = VegaSurface)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = provider.name,
                        color = VegaText,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = provider.kind,
                        color = VegaSubText,
                        fontSize = 13.sp
                    )
                    Text(
                        text = provider.status,
                        color = Tomato,
                        fontSize = 12.sp
                    )
                }
            }
        }
        item {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Phase 1 build — app base is ready. Provider and extension engines plug in next.",
                color = VegaSubText,
                fontSize = 12.sp
            )
        }
    }
}
