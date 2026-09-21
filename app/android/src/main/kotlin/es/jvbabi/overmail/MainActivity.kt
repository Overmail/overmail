package es.jvbabi.overmail

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.icerock.moko.permissions.PermissionsController
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.core.component.KoinComponent
import org.koin.core.context.loadKoinModules
import org.koin.core.qualifier.named
import org.koin.dsl.module

class MainActivity : ComponentActivity(), KoinComponent {

    companion object {
        /** Whether the activity is in the foreground, see `ApplicationRepositoryImpl`. */
        val isVisible = MutableStateFlow(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val permissionsController = PermissionsController(
            applicationContext = applicationContext
        )

        permissionsController.bind(this)

        // Custom Tabs and the share sheet need an activity, not the application context.
        loadKoinModules(module {
            single(named(KOIN_ACTIVITY_CONTEXT)) { this@MainActivity as Context }
            single { permissionsController }
        })

        isVisible.value = true

        setContent {
            App()
        }
    }

    override fun onResume() {
        super.onResume()
        isVisible.value = true
    }

    override fun onPause() {
        super.onPause()
        isVisible.value = false
    }

    override fun onStop() {
        super.onStop()
        isVisible.value = false
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
