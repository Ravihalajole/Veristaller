package dev.veristaller.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.veristaller.domain.model.SplitMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class Settings(
    val dryRun: Boolean = false,
    val installerPackage: String = "com.android.vending",
    val clearOat: Boolean = true,
    val splitMode: SplitMode = SplitMode.SMART,
    val dynamicColor: Boolean = false,
    val keepScreenOn: Boolean = true,
    val disclosureAccepted: Boolean = false,
    val theme: String = "system",
    val vibrate: Boolean = true,
    val verboseLogs: Boolean = false,
    val smokeTest: Boolean = false,
)

class SettingsRepository(private val context: Context) {

    val flow: Flow<Settings> = context.settingsDataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it.toSettings() }

    suspend fun update(transform: (Settings) -> Settings) {
        context.settingsDataStore.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[Keys.DRY_RUN] = next.dryRun
            prefs[Keys.INSTALLER] = next.installerPackage.trim()
            prefs[Keys.CLEAR_OAT] = next.clearOat
            prefs[Keys.SPLIT_MODE] = next.splitMode.name
            prefs[Keys.DYNAMIC_COLOR] = next.dynamicColor
            prefs[Keys.KEEP_SCREEN_ON] = next.keepScreenOn
            prefs[Keys.DISCLOSURE] = next.disclosureAccepted
            prefs[Keys.THEME] = next.theme
            prefs[Keys.VIBRATE] = next.vibrate
            prefs[Keys.VERBOSE] = next.verboseLogs
            prefs[Keys.SMOKE] = next.smokeTest
        }
    }

    private fun Preferences.toSettings(): Settings = Settings(
        dryRun = this[Keys.DRY_RUN] ?: false,
        installerPackage = this[Keys.INSTALLER] ?: "com.android.vending",
        clearOat = this[Keys.CLEAR_OAT] ?: true,
        splitMode = runCatching {
            SplitMode.valueOf(this[Keys.SPLIT_MODE] ?: SplitMode.SMART.name)
        }.getOrDefault(SplitMode.SMART),
        dynamicColor = this[Keys.DYNAMIC_COLOR] ?: false,
        keepScreenOn = this[Keys.KEEP_SCREEN_ON] ?: true,
        disclosureAccepted = this[Keys.DISCLOSURE] ?: false,
        theme = this[Keys.THEME] ?: "system",
        vibrate = this[Keys.VIBRATE] ?: true,
        verboseLogs = this[Keys.VERBOSE] ?: false,
        smokeTest = this[Keys.SMOKE] ?: false,
    )

    private object Keys {
        val DRY_RUN = booleanPreferencesKey("dry_run")
        val INSTALLER = stringPreferencesKey("installer_package")
        val CLEAR_OAT = booleanPreferencesKey("clear_oat")
        val SPLIT_MODE = stringPreferencesKey("split_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val DISCLOSURE = booleanPreferencesKey("disclosure_accepted")
        val THEME = stringPreferencesKey("theme")
        val VIBRATE = booleanPreferencesKey("vibrate")
        val VERBOSE = booleanPreferencesKey("verbose_logs")
        val SMOKE = booleanPreferencesKey("smoke_test")
    }
}
