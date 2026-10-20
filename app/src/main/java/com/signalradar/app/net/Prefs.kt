package com.signalradar.app.net

import android.content.Context

/** Small persisted settings for the ALPR map. */
object Prefs {
    private const val FILE = "signalradar"
    private const val KEY_AUTO = "alpr_auto"
    private const val KEY_RADIUS_IDX = "alpr_radius_idx"
    private const val KEY_PINE_HOST = "pine_host"
    private const val KEY_PINE_USER = "pine_user"
    private const val KEY_PINE_PASS = "pine_pass"
    private const val KEY_PINE_BAND = "pine_band"

    val RADII = listOf(1000, 3000, 10000, 25000)

    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Load the ALPR map automatically when the app opens. Default on. */
    fun autoLoadAlpr(ctx: Context): Boolean = p(ctx).getBoolean(KEY_AUTO, true)
    fun setAutoLoadAlpr(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean(KEY_AUTO, v).apply()

    fun radiusIdx(ctx: Context): Int = p(ctx).getInt(KEY_RADIUS_IDX, 1).coerceIn(0, RADII.size - 1)
    fun setRadiusIdx(ctx: Context, v: Int) = p(ctx).edit().putInt(KEY_RADIUS_IDX, v).apply()

    fun radius(ctx: Context): Int = RADII[radiusIdx(ctx)]

    fun pineHost(ctx: Context): String = p(ctx).getString(KEY_PINE_HOST, "172.16.42.1") ?: "172.16.42.1"
    fun setPineHost(ctx: Context, v: String) = p(ctx).edit().putString(KEY_PINE_HOST, v).apply()

    fun pineUser(ctx: Context): String = p(ctx).getString(KEY_PINE_USER, "root") ?: "root"
    fun setPineUser(ctx: Context, v: String) = p(ctx).edit().putString(KEY_PINE_USER, v).apply()

    fun pinePass(ctx: Context): String = p(ctx).getString(KEY_PINE_PASS, "") ?: ""
    fun setPinePass(ctx: Context, v: String) = p(ctx).edit().putString(KEY_PINE_PASS, v).apply()

    fun pineBand(ctx: Context): Int = p(ctx).getInt(KEY_PINE_BAND, 2).coerceIn(0, 2)
    fun setPineBand(ctx: Context, v: Int) = p(ctx).edit().putInt(KEY_PINE_BAND, v).apply()
}
