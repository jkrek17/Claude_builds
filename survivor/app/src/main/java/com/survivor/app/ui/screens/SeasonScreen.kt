package com.survivor.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.survivor.app.ui.AppViewModel

/** Season = Planner (timeline of the optimized route) and Grid (32x18 matrix), as tabs of one screen so
 *  the bottom bar stays at 5 destinations. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeasonScreen(vm: AppViewModel) {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Planner") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Grid") })
        }
        if (tab == 0) PlannerScreen(vm) else GridScreen(vm)
    }
}
