package com.mtajchert.carom.app.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mtajchert.carom.app.core.designsystem.R

/** Which glyph of the Carom mark to draw. */
enum class CaromMarkState(@DrawableRes val drawable: Int) {
    Live(R.drawable.ic_carom_mark),
    Paused(R.drawable.ic_carom_mark_paused),
    Offline(R.drawable.ic_carom_mark_offline),
}

/** The Carom "two-rail C" brand mark, tinted with [tint] (primary by default). */
@Composable
fun CaromMark(
    modifier: Modifier = Modifier,
    state: CaromMarkState = CaromMarkState.Live,
    size: Dp = 24.dp,
    tint: Color = MaterialTheme.colorScheme.primary,
    contentDescription: String? = null,
) {
    Image(
        painter = painterResource(state.drawable),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(tint),
    )
}
