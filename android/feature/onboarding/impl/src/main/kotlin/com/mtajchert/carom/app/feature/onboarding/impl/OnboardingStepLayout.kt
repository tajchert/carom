package com.mtajchert.carom.app.feature.onboarding.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.mtajchert.carom.app.core.designsystem.component.CaromMark
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mtajchert.carom.app.core.designsystem.component.CaromButton
import com.mtajchert.carom.app.core.designsystem.component.CaromTextButton

internal enum class StepTone { NEUTRAL, SUCCESS, WARNING }

internal class StepAction(val text: String, val onClick: () -> Unit, val loading: Boolean = false, val enabled: Boolean = true)

/**
 * One calm, centred step: an icon badge, a headline, a short body, optional content, and at most
 * one primary action with an optional quiet secondary action pinned to the bottom.
 */
@Composable
internal fun OnboardingStepLayout(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    brandMark: Boolean = false,
    tone: StepTone = StepTone.NEUTRAL,
    body: String? = null,
    primary: StepAction? = null,
    secondary: StepAction? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(modifier = modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (brandMark) {
                    Spacer(Modifier.height(24.dp))
                    Box(
                        modifier = Modifier
                            .size(112.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(36.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CaromMark(size = 72.dp, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Spacer(Modifier.height(8.dp))
                } else if (icon != null) {
                    IconBadge(icon, tone)
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    text = title,
                    style = if (brandMark) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
                if (body != null) {
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                content()
            }
        }
        if (primary != null || secondary != null) {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, bottom = 24.dp, top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                primary?.let {
                    CaromButton(
                        onClick = it.onClick,
                        text = it.text,
                        isLoading = it.loading,
                        enabled = it.enabled && !it.loading,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                secondary?.let {
                    CaromTextButton(onClick = it.onClick, text = it.text, enabled = it.enabled, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun IconBadge(icon: ImageVector, tone: StepTone) {
    val colors = MaterialTheme.colorScheme
    val (container: Color, content: Color) = when (tone) {
        StepTone.NEUTRAL -> colors.primaryContainer to colors.onPrimaryContainer
        StepTone.SUCCESS -> colors.tertiaryContainer to colors.onTertiaryContainer
        StepTone.WARNING -> colors.errorContainer to colors.onErrorContainer
    }
    Box(
        modifier = Modifier
            .size(72.dp)
            .background(container, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(36.dp))
    }
}
