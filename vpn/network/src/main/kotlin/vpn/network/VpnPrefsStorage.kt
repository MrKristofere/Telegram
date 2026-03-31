package vpn.network

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

// TODO: унести отсюда
class VpnPrefsStorage(context: Context) {

    companion object {
        private const val PREFS_NAME = "vpn_sdk_prefs"

        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_CACHED_CONFIG = "cached_app_config"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getDeviceId(): String? = prefs.getString(KEY_DEVICE_ID, null)

    fun saveDeviceId(id: String) {
        prefs.edit { putString(KEY_DEVICE_ID, id) }
    }

    fun getCachedConfig(): String? = prefs.getString(KEY_CACHED_CONFIG, null)

    fun saveCachedConfig(json: String) {
        prefs.edit { putString(KEY_CACHED_CONFIG, json) }
    }
}
