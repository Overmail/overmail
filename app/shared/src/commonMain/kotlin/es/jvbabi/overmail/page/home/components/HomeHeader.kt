package es.jvbabi.overmail.page.home.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import com.phosphor.icons.PhIcons
import com.phosphor.icons.regular.List
import es.jvbabi.overmail.domain.model.OvermailAccount
import es.jvbabi.overmail.page.home.Greeting
import es.jvbabi.overmail.page.home.PREVIEW_ACCOUNT
import es.jvbabi.overmail.ui.theme.AppTheme
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import overmail.app.shared.generated.resources.Res
import overmail.app.shared.generated.resources.app_icon
import overmail.app.shared.generated.resources.home_greeting_day
import overmail.app.shared.generated.resources.home_greeting_evening
import overmail.app.shared.generated.resources.home_greeting_morning
import overmail.app.shared.generated.resources.home_greeting_night

val HEADER_HEIGHT = 64.dp

@Composable
fun HomeHeader(
    currentUser: OvermailAccount?,
    greeting: Greeting,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = {},
            modifier = Modifier.padding(end = 8.dp),
        ) {
            Icon(
                imageVector = PhIcons.Regular.List,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        }
        Image(
            painter = painterResource(Res.drawable.app_icon),
            contentDescription = null,
            modifier = Modifier
                .padding(end = 8.dp)
                .size(HEADER_HEIGHT - 2*16.dp)
                .clip(RoundedCornerShape(8.dp))
        )
        Column {
            Text(
                text = "Overmail",
                style = MaterialTheme.typography.bodyLarge,
            )
            if (currentUser != null) Text(
                text = stringResource(
                    when (greeting) {
                        Greeting.Night -> Res.string.home_greeting_night
                        Greeting.Morning -> Res.string.home_greeting_morning
                        Greeting.Day -> Res.string.home_greeting_day
                        Greeting.Evening -> Res.string.home_greeting_evening
                    },
                    currentUser.firstName,
                ),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
@Preview
private fun HomeHeaderPreview() {
    AppTheme(dynamicColor = false) {
        Surface {
            HomeHeader(
                currentUser = PREVIEW_ACCOUNT,
                greeting = Greeting.Morning,
            )
        }
    }
}
