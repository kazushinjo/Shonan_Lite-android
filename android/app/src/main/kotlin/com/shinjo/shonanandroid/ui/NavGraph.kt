package com.shinjo.shonanandroid.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.shinjo.shonanandroid.AppViewModel

@Composable
fun ShonanNavHost(viewModel: AppViewModel) {
    val navController = rememberNavController()
    viewModel.plutoCapabilityNotice?.let { notice ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.plutoCapabilityNotice = null },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { viewModel.plutoCapabilityNotice = null }) {
                    androidx.compose.material3.Text("OK")
                }
            },
            title = { androidx.compose.material3.Text(viewModel.settings.t("変調方式", "Modulation")) },
            text = { androidx.compose.material3.Text(notice) },
        )
    }
    NavHost(navController = navController, startDestination = "home") {
        composable("home") { HomeScreen(viewModel, navController) }
        composable("tx") { TxScreen(viewModel, navController) }
        composable("rx") { RxScreen(viewModel, navController) }
        composable("frequency") { FrequencySettingsScreen(viewModel, navController) }
        composable("find") { RssiScreen(viewModel, navController) }
        composable("symbolrate") { SymbolRateSettingsScreen(viewModel, navController) }
        composable("fec") { FECSettingsScreen(viewModel, navController) }
        composable("modulation") { ModulationSettingsScreen(viewModel, navController) }
        composable("videosource") { VideoSourceSettingsScreen(viewModel, navController) }
        composable("streamoutput") { StreamOutputSettingsScreen(viewModel, navController) }
        composable("rxgain") { RxGainSettingsScreen(viewModel, navController) }
        composable("txpower") { TxPowerSettingsScreen(viewModel, navController) }
        composable("manual") { ManualScreen(viewModel, navController) }
        composable("testequipment") { TestEquipmentScreen(viewModel, navController) }
        composable("settings") { SettingsScreen(viewModel, navController) }
        composable("langstone") { LangstoneScreen(viewModel, navController) }
    }
}
