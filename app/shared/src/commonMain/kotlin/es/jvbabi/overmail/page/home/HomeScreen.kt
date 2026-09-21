package es.jvbabi.overmail.page.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.phosphor.icons.PhIcons
import com.phosphor.icons.regular.MagnifyingGlass
import es.jvbabi.overmail.domain.model.ViewState
import es.jvbabi.overmail.page.home.components.filter.ViewController

@Composable
fun HomeScreen() {
    HomeContent()
}

@Composable
private fun HomeContent() {
    var viewState by remember { mutableStateOf(ViewState()) }

    Scaffold { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 16.dp + innerPadding.calculateBottomPadding())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextField(
                    value = "",
                    onValueChange = {},
                    leadingIcon = {
                        Icon(
                            imageVector = PhIcons.Regular.MagnifyingGlass,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    placeholder = { Text("Q2 Budget report") },
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth(),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        errorIndicatorColor = Color.Transparent,
                    ),
                    shape = RoundedCornerShape(percent = 50),
                )
                ViewController(
                    viewState = viewState,
                    onFilterChange = { viewState = viewState.copy(filter = it) },
                    contentPadding = PaddingValues(horizontal = 16.dp),
                )
            }
        }
    }
}

@Composable
@Preview
private fun HomeContentPreview() {
    HomeContent()
}
