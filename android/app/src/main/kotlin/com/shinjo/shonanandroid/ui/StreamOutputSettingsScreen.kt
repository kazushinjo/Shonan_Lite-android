package com.shinjo.shonanandroid.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.shinjo.shonanandroid.AppViewModel

/**
 * IMEが全角数字/全角ピリオドで確定すること(日本語Gboard等でIPアドレス欄に入力した際に発生)
 * があり、UnknownHostExceptionでPlutoへ接続できなくなるため、半角へ正規化しIPアドレスに
 * 使わない文字を除去する。
 */
private fun normalizeIpInput(raw: String): String =
    raw.map { c ->
        when (c) {
            in '０'..'９' -> '0' + (c - '０')
            '．' -> '.'
            else -> c
        }
    }.filter { it.isDigit() || it == '.' }.joinToString("")

/** 配信先（送信先IP/ポート、受信ポート）の設定画面。 */
@Composable
fun StreamOutputSettingsScreen(viewModel: AppViewModel, navController: NavHostController) {
    val settings = viewModel.settings

    SettingsSubScreen(
        title = settings.t("配信先", "Stream Output"),
        onBack = { navController.popBackStack("home", false) },
        homeLabel = settings.t("ホームへ戻る", "Home"),
    ) {
        Text(settings.t("送信先(Pluto Tx)", "TX Destination (Pluto Tx)"), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = settings.txDestinationIP,
            onValueChange = { viewModel.updateSettings { s -> s.copy(txDestinationIP = normalizeIpInput(it)) } },
            label = { Text(settings.t("IPアドレス", "IP Address")) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        OutlinedTextField(
            value = settings.txDestinationPort.toString(),
            onValueChange = { value -> value.toIntOrNull()?.let { viewModel.updateSettings { s -> s.copy(txDestinationPort = it) } } },
            label = { Text(settings.t("ポート", "Port")) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        Text(settings.t("受信設定", "Receive Settings"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
        OutlinedTextField(
            value = settings.rxListenPort.toString(),
            onValueChange = { value -> value.toIntOrNull()?.let { viewModel.updateSettings { s -> s.copy(rxListenPort = it) } } },
            label = { Text(settings.t("TSポート", "TS Port")) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        OutlinedTextField(
            value = settings.rxStatusPort.toString(),
            onValueChange = { value -> value.toIntOrNull()?.let { viewModel.updateSettings { s -> s.copy(rxStatusPort = it) } } },
            label = { Text(settings.t("ステータスポート", "Status Port")) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
}
