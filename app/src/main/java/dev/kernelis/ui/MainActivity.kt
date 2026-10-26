package dev.kernelis.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import dev.kernelis.ui.theme.KernelisTheme

/**
 * Single-activity entry point.
 *
 * All navigation happens inside [KernelisApp] via Navigation Compose. Edge-to-edge is
 * enabled because the dashboard is designed to bleed under the status bar on OLED.
 */
@AndroidEntryPoint
public class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            KernelisTheme {
                KernelisApp()
            }
        }
    }
}
