package app.simpleguide

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.simpleguide.ui.CitiesScreen
import app.simpleguide.ui.GuideTheme
import app.simpleguide.ui.SettingsScreen
import app.simpleguide.ui.TourScreen
import app.simpleguide.util.AppLanguage

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as GuideApp
        setContent {
            GuideTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.AutoMirrored.Filled.DirectionsWalk, null) }, label = { Text(stringResource(R.string.tab_tour)) })
                            NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Default.Download, null) }, label = { Text(stringResource(R.string.tab_cities)) })
                            NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Default.Settings, null) }, label = { Text(stringResource(R.string.tab_settings)) })
                        }
                    },
                ) { padding ->
                    val m = Modifier.padding(padding)
                    when (tab) {
                        0 -> TourScreen(app, m, onOpenSettings = { tab = 2 })
                        1 -> CitiesScreen(app, m)
                        else -> SettingsScreen(app, m)
                    }
                }
            }
        }
    }
}
