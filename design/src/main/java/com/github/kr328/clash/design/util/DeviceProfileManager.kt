package com.github.kr328.clash.design.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class DeviceProfile(
    val id: Int,
    val name: String,
    val config: String,
    val isDefault: Boolean = false
)

object DeviceProfileManager {
    private const val PREF_NAME = "device_profiles_prefs"
    private const val KEY_DATA = "profiles_json"

    fun getDefaultProfiles(context: Context): List<DeviceProfile> = listOf(
        DeviceProfile(1, "v2rayTun (Android)", "UA=v2raytun/android&Model=RMX2063&x-hwid=14EC1A0638D038E4&x-ver-os=11&x-app-version=5.21.68&accept-encoding=gzip", true),
        DeviceProfile(2, "Happ (Android)", "UA=Happ/3.16.1/Android/1743595&Model=RMX2063&x-hwid=a35bb23fdaadd515&x-ver-os=11&x-device-os=Android&x-device-locale=ru&accept-encoding=gzip", true),
        DeviceProfile(3, "Happ (Windows ПК)", "UA=Happ/2.7.0/Windows/2604031533607&Model=WIN-ISSKDS27EMN_x86_64&x-hwid=ac87c7e4-d900-4c3a-812d-83bce4c727c6&x-ver-os=11_10.0.26200&x-device-os=Windows&x-app-version=2.7.0&x-device-locale=RU&accept-encoding=gzip%2C%20deflate", true)
    )

    fun loadProfiles(context: Context): MutableList<DeviceProfile> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(KEY_DATA, null) ?: return getDefaultProfiles(context).toMutableList()

        return try {
            val list = mutableListOf<DeviceProfile>()
            val array = JSONArray(jsonString)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(DeviceProfile(obj.getInt("id"), obj.getString("name"), obj.getString("config"), obj.getBoolean("isDefault")))
            }
            list
        } catch (e: Exception) {
            getDefaultProfiles(context).toMutableList()
        }
    }

    fun saveProfiles(context: Context, profiles: List<DeviceProfile>) {
        val array = JSONArray()
        profiles.forEach {
            val obj = JSONObject()
            obj.put("id", it.id)
            obj.put("name", it.name)
            obj.put("config", it.config)
            obj.put("isDefault", it.isDefault)
            array.put(obj)
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putString(KEY_DATA, array.toString()).apply()
    }
}