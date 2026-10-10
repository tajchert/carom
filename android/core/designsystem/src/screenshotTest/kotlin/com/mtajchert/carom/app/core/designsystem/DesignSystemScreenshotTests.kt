package com.mtajchert.carom.app.core.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.mtajchert.carom.app.core.designsystem.component.CaromButton
import com.mtajchert.carom.app.core.designsystem.component.CaromCard
import com.mtajchert.carom.app.core.designsystem.component.CaromElevatedCard
import com.mtajchert.carom.app.core.designsystem.component.CaromEmptyState
import com.mtajchert.carom.app.core.designsystem.component.CaromErrorState
import com.mtajchert.carom.app.core.designsystem.component.CaromLoadingIndicator
import com.mtajchert.carom.app.core.designsystem.component.CaromOutlinedButton
import com.mtajchert.carom.app.core.designsystem.component.CaromSmallLoadingIndicator
import com.mtajchert.carom.app.core.designsystem.component.CaromTextButton
import com.mtajchert.carom.app.core.designsystem.component.CaromTopAppBar
import com.mtajchert.carom.app.core.designsystem.theme.CaromTheme

@Preview(name = "Light", showBackground = true, widthDp = 360)
@Preview(
    name = "Dark",
    showBackground = true,
    widthDp = 360,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
private annotation class ComponentThemes

@PreviewTest
@ComponentThemes
@Composable
fun ButtonsScreenshotTest() {
    CaromTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CaromButton(onClick = {}, text = "Primary")
            CaromButton(onClick = {}, text = "Loading", isLoading = true)
            CaromButton(onClick = {}, text = "Disabled", enabled = false)
            CaromOutlinedButton(onClick = {}, text = "Outlined")
            CaromTextButton(onClick = {}, text = "Text")
        }
    }
}

@PreviewTest
@ComponentThemes
@Composable
fun CardsScreenshotTest() {
    CaromTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CaromCard(onClick = {}) {
                Text("Standard card", style = MaterialTheme.typography.titleMedium)
                Text("Supporting content")
            }
            CaromElevatedCard {
                Text("Elevated card", style = MaterialTheme.typography.titleMedium)
                Text("Prominent supporting content")
            }
        }
    }
}

@PreviewTest
@ComponentThemes
@Composable
fun LoadingIndicatorsScreenshotTest() {
    CaromTheme {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            CaromLoadingIndicator(modifier = Modifier.size(80.dp))
            CaromSmallLoadingIndicator()
        }
    }
}

@PreviewTest
@Preview(name = "Empty and error", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
fun EmptyAndErrorStatesScreenshotTest() {
    CaromTheme {
        Column {
            CaromEmptyState(
                icon = Icons.Outlined.Inbox,
                title = "Nothing here",
                description = "Create the first item to get started.",
                actionLabel = "Create item",
                onAction = {},
                modifier = Modifier.weight(1f),
            )
            CaromErrorState(
                message = "The content could not be loaded.",
                onRetry = {},
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@PreviewTest
@ComponentThemes
@Composable
fun TopAppBarsScreenshotTest() {
    CaromTheme {
        Column {
            CaromTopAppBar(title = "Overview")
            CaromTopAppBar(title = "Details", onNavigationClick = {})
        }
    }
}
