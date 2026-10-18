package com.kitalonlabs.neogold.engine.camera.device

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Manufacturer brands requiring custom battery and background app settings.
 * Document 1 Section 13, Document 6 S05, TC-DEV-01, TC-DEV-09.
 */
enum class DeviceBrand {
    TECNO,
    INFINIX,
    ITEL,
    XIAOMI,
    SAMSUNG,
    SYMPHONY,
    WALTON,
    GENERIC;

    companion object {
        fun detect(manufacturer: String = Build.MANUFACTURER, brand: String = Build.BRAND): DeviceBrand {
            val combined = "$manufacturer $brand".lowercase()
            return when {
                combined.contains("tecno") -> TECNO
                combined.contains("infinix") -> INFINIX
                combined.contains("itel") -> ITEL
                combined.contains("xiaomi") || combined.contains("redmi") || combined.contains("poco") -> XIAOMI
                combined.contains("samsung") -> SAMSUNG
                combined.contains("symphony") -> SYMPHONY
                combined.contains("walton") -> WALTON
                else -> GENERIC
            }
        }
    }
}

/**
 * Brand-specific guidance and intent provider for battery settings.
 */
data class BatteryGuidance(
    val brand: DeviceBrand,
    val brandDisplayName: String,
    val skinName: String,
    val titleEn: String,
    val titleBn: String,
    val stepsEn: List<String>,
    val stepsBn: List<String>,
    val primaryIntentFactory: (Context) -> Intent?,
    val fallbackIntentFactory: (Context) -> Intent
)

object DeviceBatteryGuidance {

    fun getGuidance(
        manufacturer: String = Build.MANUFACTURER,
        brand: String = Build.BRAND
    ): BatteryGuidance {
        val detectedBrand = DeviceBrand.detect(manufacturer, brand)
        return when (detectedBrand) {
            DeviceBrand.TECNO -> BatteryGuidance(
                brand = DeviceBrand.TECNO,
                brandDisplayName = "TECNO",
                skinName = "HiOS",
                titleEn = "Keep NeoGold reminders working on TECNO (HiOS)",
                titleBn = "টেকনো (HiOS) ফোনে নিওগোল্ডের রিমাইন্ডার চালু রাখুন",
                stepsEn = listOf(
                    "Open Settings > Battery Lab (or Phone Master app).",
                    "Tap 'Auto-start management' and enable NeoGold.",
                    "Tap Battery Optimization > NeoGold > Don't optimize."
                ),
                stepsBn = listOf(
                    "সেটিংস > ব্যাটারি ল্যাব (বা ফোন মাস্টার অ্যাপ) খুলুন।",
                    "'অটো-স্টার্ট ম্যানেজমেন্ট'-এ গিয়ে নিওগোল্ড চালু করুন।",
                    "ব্যাটারি অপ্টিমাইজেশন > নিওগোল্ড > অপ্টিমাইজ করবেন না নির্বাচন করুন।"
                ),
                primaryIntentFactory = { context ->
                    createExplicitIntent(
                        "com.transsion.phonemaster",
                        "com.transsion.phonemaster.autostart.AutoStartManageActivity"
                    )
                },
                fallbackIntentFactory = { context -> createPackageSettingsIntent(context) }
            )

            DeviceBrand.INFINIX -> BatteryGuidance(
                brand = DeviceBrand.INFINIX,
                brandDisplayName = "Infinix",
                skinName = "XOS",
                titleEn = "Keep NeoGold reminders working on Infinix (XOS)",
                titleBn = "ইনফিনিক্স (XOS) ফোনে নিওগোল্ডের রিমাইন্ডার চালু রাখুন",
                stepsEn = listOf(
                    "Open Settings > Battery Lab (or Power Marathon).",
                    "Tap 'Auto-start management' and allow NeoGold.",
                    "Exclude NeoGold from background freeze restrictions."
                ),
                stepsBn = listOf(
                    "সেটিংস > ব্যাটারি ল্যাব (বা পাওয়ার ম্যারাথন) খুলুন।",
                    "'অটো-স্টার্ট ম্যানেজমেন্ট'-এ গিয়ে নিওগোল্ড অনুমোদন করুন।",
                    "ব্যাকগ্রাউন্ড ফ্রিজ তালিকা থেকে নিওগোল্ড বাদ দিন।"
                ),
                primaryIntentFactory = { context ->
                    createExplicitIntent(
                        "com.transsion.phonemaster",
                        "com.transsion.phonemaster.autostart.AutoStartManageActivity"
                    )
                },
                fallbackIntentFactory = { context -> createPackageSettingsIntent(context) }
            )

            DeviceBrand.ITEL -> BatteryGuidance(
                brand = DeviceBrand.ITEL,
                brandDisplayName = "itel",
                skinName = "itel OS",
                titleEn = "Keep NeoGold reminders working on itel",
                titleBn = "আইটেল ফোনে নিওগোল্ডের রিমাইন্ডার চালু রাখুন",
                stepsEn = listOf(
                    "Open Settings > Battery Saver (or Phone Master).",
                    "Tap 'Auto-start management' and enable NeoGold.",
                    "Set High power consumption in background to 'Allow'."
                ),
                stepsBn = listOf(
                    "সেটিংস > ব্যাটারি সেভার (বা ফোন মাস্টার) খুলুন।",
                    "'অটো-স্টার্ট ম্যানেজমেন্ট'-এ গিয়ে নিওগোল্ড চালু করুন।",
                    "ব্যাকগ্রাউন্ডে ব্যবহারের জন্য 'অনুমোদন করুন' নির্বাচন করুন।"
                ),
                primaryIntentFactory = { context ->
                    createExplicitIntent(
                        "com.transsion.phonemaster",
                        "com.transsion.phonemaster.autostart.AutoStartManageActivity"
                    )
                },
                fallbackIntentFactory = { context -> createPackageSettingsIntent(context) }
            )

            DeviceBrand.XIAOMI -> BatteryGuidance(
                brand = DeviceBrand.XIAOMI,
                brandDisplayName = "Xiaomi / Redmi",
                skinName = "MIUI / HyperOS",
                titleEn = "Keep NeoGold reminders working on Xiaomi (HyperOS / MIUI)",
                titleBn = "শাওমি (HyperOS / MIUI) ফোনে নিওগোল্ডের রিমাইন্ডার চালু রাখুন",
                stepsEn = listOf(
                    "Open Settings > Apps > Manage apps > NeoGold.",
                    "Turn on 'Autostart'.",
                    "Under Battery saver, select 'No restrictions'."
                ),
                stepsBn = listOf(
                    "সেটিংস > অ্যাপস > ম্যানেজ অ্যাপস > নিওগোল্ড খুলুন।",
                    "'অটোস্টার্ট' চালু করুন।",
                    "ব্যাটারি সেভারে 'নো রেস্ট্রিকশন' (কোনো বাধা নেই) নির্বাচন করুন।"
                ),
                primaryIntentFactory = { context ->
                    createExplicitIntent(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"
                    )
                },
                fallbackIntentFactory = { context -> createPackageSettingsIntent(context) }
            )

            DeviceBrand.SAMSUNG -> BatteryGuidance(
                brand = DeviceBrand.SAMSUNG,
                brandDisplayName = "Samsung",
                skinName = "One UI",
                titleEn = "Keep NeoGold reminders working on Samsung (One UI)",
                titleBn = "স্যামসাং (One UI) ফোনে নিওগোল্ডের রিমাইন্ডার চালু রাখুন",
                stepsEn = listOf(
                    "Open Settings > Battery and device care > Battery.",
                    "Tap 'Background usage limits'.",
                    "Tap 'Never sleeping apps' and add NeoGold."
                ),
                stepsBn = listOf(
                    "সেটিংস > ব্যাটারি অ্যান্ড ডিভাইস কেয়ার > ব্যাটারি খুলুন।",
                    "'ব্যাকগ্রাউন্ড ইউসেজ লিমিটস'-এ যান।",
                    "'নেভার স্লিপিং অ্যাপস'-এ নিওগোল্ড যোগ করুন।"
                ),
                primaryIntentFactory = { context ->
                    createExplicitIntent(
                        "com.samsung.android.lool",
                        "com.samsung.android.sm.battery.ui.BatteryActivity"
                    )
                },
                fallbackIntentFactory = { context -> createPackageSettingsIntent(context) }
            )

            DeviceBrand.SYMPHONY -> BatteryGuidance(
                brand = DeviceBrand.SYMPHONY,
                brandDisplayName = "Symphony",
                skinName = "Symphony Android",
                titleEn = "Keep NeoGold reminders working on Symphony",
                titleBn = "সিম্ফনি ফোনে নিওগোল্ডের রিমাইন্ডার চালু রাখুন",
                stepsEn = listOf(
                    "Open Settings > Battery.",
                    "Select Battery optimization > NeoGold > Don't optimize.",
                    "Ensure background data and autostart are allowed."
                ),
                stepsBn = listOf(
                    "সেটিংস > ব্যাটারি খুলুন।",
                    "ব্যাটারি অপ্টিমাইজেশন > নিওগোল্ড > অপ্টিমাইজ করবেন না নির্বাচন করুন।",
                    "ব্যাকগ্রাউন্ড ডেটা ব্যবহারের অনুমতি দিন।"
                ),
                primaryIntentFactory = { context -> createBatteryOptimizationIntent() },
                fallbackIntentFactory = { context -> createPackageSettingsIntent(context) }
            )

            DeviceBrand.WALTON -> BatteryGuidance(
                brand = DeviceBrand.WALTON,
                brandDisplayName = "Walton",
                skinName = "Walton Android",
                titleEn = "Keep NeoGold reminders working on Walton",
                titleBn = "ওয়ালটন ফোনে নিওগোল্ডের রিমাইন্ডার চালু রাখুন",
                stepsEn = listOf(
                    "Open Settings > Battery > Smart power saving.",
                    "Under Background app management, allow NeoGold.",
                    "Set NeoGold to unrestricted background activity."
                ),
                stepsBn = listOf(
                    "সেটিংস > ব্যাটারি > স্মার্ট পাওয়ার সেভিং খুলুন।",
                    "ব্যাকগ্রাউন্ড অ্যাপ ম্যানেজমেন্টে নিওগোল্ড অনুমোদন করুন।",
                    "সীমাহীন ব্যাকগ্রাউন্ড কার্যকলাপ নির্বাচন করুন।"
                ),
                primaryIntentFactory = { context -> createBatteryOptimizationIntent() },
                fallbackIntentFactory = { context -> createPackageSettingsIntent(context) }
            )

            DeviceBrand.GENERIC -> BatteryGuidance(
                brand = DeviceBrand.GENERIC,
                brandDisplayName = "Android Device",
                skinName = "Stock Android",
                titleEn = "Keep NeoGold reminders working",
                titleBn = "নিওগোল্ডের রিমাইন্ডার চালু রাখুন",
                stepsEn = listOf(
                    "Open Settings > Apps > NeoGold > Battery.",
                    "Select 'Unrestricted' or 'Not optimized'."
                ),
                stepsBn = listOf(
                    "সেটিংস > অ্যাপস > নিওগোল্ড > ব্যাটারি খুলুন।",
                    "'আনরেস্ট্রিক্টেড' বা 'অপ্টিমাইজ করবেন না' নির্বাচন করুন।"
                ),
                primaryIntentFactory = { context -> createBatteryOptimizationIntent() },
                fallbackIntentFactory = { context -> createPackageSettingsIntent(context) }
            )
        }
    }

    private fun createExplicitIntent(packageName: String, className: String): Intent? {
        return try {
            Intent().apply {
                component = ComponentName(packageName, className)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun createBatteryOptimizationIntent(): Intent {
        return Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    private fun createPackageSettingsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }
}
