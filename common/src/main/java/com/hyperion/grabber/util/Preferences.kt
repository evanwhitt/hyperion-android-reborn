package com.hyperion.grabber.common.util

import android.content.Context
import android.content.res.Resources
import androidx.preference.PreferenceManager
import androidx.annotation.StringRes
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/** Wrapper around SharedPreferences which allows for default values defined in Resources
 * Main purpose is that defaults are defined in a centralized location and that preferences are
 * accessed through a unified interface
 * Now includes in-memory caching to reduce disk I/O */
class Preferences(context: Context) {

    private val preferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val resources = context.resources
    
    // In-memory cache to reduce SharedPreferences disk I/O
    private val stringCache = ConcurrentHashMap<String, String?>()
    private val intCache = ConcurrentHashMap<String, Int>()
    private val boolCache = ConcurrentHashMap<String, Boolean>()
    private val cacheHits = ConcurrentHashMap<String, Boolean>()

    fun contains(@StringRes keyResourceId: Int): Boolean = preferences.contains(key(keyResourceId))

    fun getString(@StringRes keyResourceId: Int, default: String? = null): String? {
        val keyStr = key(keyResourceId)
        
        // Return from cache if available
        if (stringCache.containsKey(keyStr)) {
            return stringCache[keyStr]
        }
        
        // Fetch from SharedPreferences and cache
        val value = preferences.getString(keyStr, default)
        // ConcurrentHashMap forbids null values, so only cache non-null results
        if (value != null) {
            stringCache[keyStr] = value
        }
        return value
    }

    fun putString(@StringRes keyResourceId: Int, value: String){
        val keyStr = key(keyResourceId)
        val edit = preferences.edit()
        edit.putString(keyStr, value)
        edit.apply()
        
        // Update cache
        stringCache[keyStr] = value
        intCache.remove(keyStr)
        boolCache.remove(keyStr)
    }

    fun getInt(@StringRes keyResourceId: Int): Int {
        val keyStr = key(keyResourceId)
        
        // Return from cache if available
        if (intCache.containsKey(keyStr)) {
            return intCache[keyStr]!!
        }
        
        val default = defaultKey(keyResourceId, "integer").let {
            if (it == 0){
                0
            } else {
                try {
                    resources.getInteger(it)
                } catch (e: Resources.NotFoundException) {
                    0
                }
            }
        }

        return getInt(keyResourceId, default)
    }

    fun getInt(@StringRes keyResourceId: Int, default: Int = 0): Int {
        val keyStr = key(keyResourceId)
        
        // Return from cache if available
        if (intCache.containsKey(keyStr)) {
            return intCache[keyStr]!!
        }
        
        val value = getString(keyResourceId)?.let { Integer.parseInt(it) } ?: default
        intCache[keyStr] = value
        return value
    }

    fun putInt(@StringRes keyResourceId: Int, value: Int){
        putString(keyResourceId, value.toString())
        val keyStr = key(keyResourceId)
        intCache[keyStr] = value
    }

    fun getBoolean(@StringRes keyResourceId: Int): Boolean {
        val keyStr = key(keyResourceId)
        
        // Return from cache if available
        if (boolCache.containsKey(keyStr)) {
            return boolCache[keyStr]!!
        }
        
        val default = defaultKey(keyResourceId, "bool").let {
            if (it == 0){
                false
            } else {
                try {
                    resources.getBoolean(it)
                } catch (e: Resources.NotFoundException) {
                    false
                }
            }
        }

        val value = preferences.getBoolean(keyStr, default)
        boolCache[keyStr] = value
        return value
    }

    fun getBoolean(@StringRes keyResourceId: Int, default: Boolean): Boolean {
        val keyStr = key(keyResourceId)
        
        // Return from cache if available
        if (boolCache.containsKey(keyStr)) {
            return boolCache[keyStr]!!
        }
        
        val value = preferences.getBoolean(keyStr, default)
        boolCache[keyStr] = value
        return value
    }

    fun putBoolean(@StringRes keyResourceId: Int, value: Boolean){
        val keyStr = key(keyResourceId)
        val edit = preferences.edit()
        edit.putBoolean(keyStr, value)
        edit.apply()
        
        // Update cache
        boolCache[keyStr] = value
        stringCache.remove(keyStr)
        intCache.remove(keyStr)
    }
    
    /**
     * Clears the in-memory cache but NOT the SharedPreferences
     * Call this when you know preferences have been updated externally
     */
    fun clearCache() {
        stringCache.clear()
        intCache.clear()
        boolCache.clear()
        Log.d("Preferences", "Cache cleared")
    }

    private fun key(keyResourceId: Int) = resources.getString(keyResourceId)

    /** @return 0 if not found, resource id otherwise */
    private fun defaultKey(keyResourceId: Int, type: String): Int {
        val defaultKeyName = resources.getResourceEntryName(keyResourceId).replace("pref_key_", "pref_default_")
        return resources.getIdentifier(defaultKeyName, type, resources.getResourcePackageName(keyResourceId))
    }

}