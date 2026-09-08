package com.survivor.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.survivor.app.ui.AppViewModel
import com.survivor.app.ui.SurvivorNavHost
import com.survivor.app.ui.theme.SurvivorTheme

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels { AppViewModel.Factory((application as SurvivorApp).repository) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SurvivorTheme { SurvivorNavHost(viewModel) } }
    }
}
