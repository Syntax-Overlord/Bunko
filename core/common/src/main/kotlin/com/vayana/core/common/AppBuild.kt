package com.vayana.core.common

import android.content.Context
import android.os.Build
import java.util.Properties

/**
 * This install's version, plus its build number: the version code suffixed with the local build date and time
 * (e.g. 3.20261013.2245), baked into assets/build-info.properties by the app's build. Sync records it so devices
 * can tell a snapshot written by a newer or older build.
 */
data class AppBuild(
    val versionName: String,
    val versionCode: Long,
    val buildNumber: String,
    /** When this build was made, in epoch millis; 0 when unknown. */
    val builtAt: Long,
) {
    /** Positive when this build is newer than [other]: a higher version code, else (same code) a later build time. */
    fun compareTo(other: AppBuild): Int = compareValuesBy(this, other, AppBuild::versionCode, AppBuild::builtAt)
}

private var cachedAppBuild: AppBuild? = null

fun Context.appBuild(): AppBuild = cachedAppBuild ?: readAppBuild().also { cachedAppBuild = it }

private fun Context.readAppBuild(): AppBuild {
    val packageInfo = packageManager.getPackageInfo(packageName, 0)
    val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageInfo.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        packageInfo.versionCode.toLong()
    }
    // Missing in builds that skip the app's build-info task (library tests): fall back to the bare version code.
    val info = runCatching { Properties().apply { assets.open(BuildInfoAsset).use(::load) } }.getOrNull()
    return AppBuild(
        versionName = packageInfo.versionName.orEmpty(),
        versionCode = versionCode,
        buildNumber = info?.getProperty("buildNumber") ?: versionCode.toString(),
        builtAt = info?.getProperty("builtAt")?.toLongOrNull() ?: 0L,
    )
}

private const val BuildInfoAsset = "build-info.properties"
