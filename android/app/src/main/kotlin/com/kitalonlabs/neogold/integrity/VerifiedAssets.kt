package com.kitalonlabs.neogold.integrity

import com.kitalonlabs.neogold.core.security.integrity.BundleLoadResult
import com.kitalonlabs.neogold.core.security.integrity.BundleManifest
import com.kitalonlabs.neogold.core.security.integrity.BundleManifestVerifier
import com.kitalonlabs.neogold.core.security.integrity.IntegrityResult
import com.kitalonlabs.neogold.engine.cardtrust.TrustList
import com.kitalonlabs.neogold.engine.cardtrust.TrustListParser
import com.kitalonlabs.neogold.engine.triage.SignoffStatus
import com.kitalonlabs.neogold.engine.triage.TriageConfig
import com.kitalonlabs.neogold.engine.triage.TriageConfigParser
import java.io.InputStream
import java.time.LocalDate

/**
 * What the app knows about its own bundled files after the startup check (doc 4 Section 12, doc 8 Section 6.5).
 *
 * The triage config here was parsed from the very bytes the signature and hashes were checked on, so what is
 * shown, saved and decided from can only be the signed config (BUG-2026-007). Anything less than a fully verified
 * bundle leaves [triageConfig] null and [triageEnabled] false: the app fails closed.
 */
class VerifiedAssets private constructor(
    val integrity: IntegrityResult,
    val triageConfig: TriageConfig?,
    val modelVersion: String? = null,
    /** The signed trust list the card and study QR checks run against; null unless the whole bundle verified. */
    val trustList: TrustList? = null
) {
    /** Screening and research may run on these files. */
    val isUsable: Boolean get() = integrity.isScreeningAllowed

    /** The bundle verified and the signed config turns triage on (PRD D10, TC-TRI-26). */
    val triageEnabled: Boolean get() = isUsable && triageConfig?.triageEnabled == true

    /**
     * Whether the screening screens may be offered today. It follows the first three steps of `TriageEngine.evaluate`
     * (switch on; signed, unless this is a debuggable build; not past `validUntil`), so the screens and the engine
     * agree and a build past its expiry date stops offering screening before the engine would refuse (H8). The engine
     * stays the authority when it evaluates a result.
     *
     * @param isPublicBuild a build that is not debuggable: its config must carry the clinical lead's sign-off
     */
    fun triageAvailable(today: LocalDate, isPublicBuild: Boolean): Boolean {
        val config = triageConfig?.takeIf { triageEnabled } ?: return false
        val signedOff = !isPublicBuild || config.clinicalSignoff.status == SignoffStatus.SIGNED
        return signedOff && !today.isAfter(config.validUntil)
    }

    companion object {
        const val TRIAGE_CONFIG = "triage-config.json"
        const val MODEL = "model.json"
        const val TRUST_LIST = "trust-list.json"

        private val MODEL_VERSION = Regex("\"modelVersion\"\\s*:\\s*\"([^\"]+)\"")

        /** Everything refused: used before the check has run. */
        val NotVerified = VerifiedAssets(IntegrityResult.MissingManifest, null)

        /**
         * Loads and checks the bundle.
         *
         * @param isDevelopmentKey the bundle was signed with a development key (BuildConfig)
         * @param isDebuggable a debuggable build may carry a development key; an installed release may not
         */
        fun load(
            verifier: BundleManifestVerifier,
            assets: (String) -> InputStream?,
            isDevelopmentKey: Boolean,
            isDebuggable: Boolean
        ): VerifiedAssets = when (val result = verifier.load(assets)) {
            is BundleLoadResult.Rejected -> VerifiedAssets(result.reason, null)
            is BundleLoadResult.Loaded -> interpret(
                manifest = result.bundle.manifest,
                triageConfigText = result.bundle.text(TRIAGE_CONFIG),
                modelText = result.bundle.text(MODEL),
                trustListText = result.bundle.text(TRUST_LIST),
                isDevelopmentKey = isDevelopmentKey,
                isDebuggable = isDebuggable
            )
        }

        /**
         * What a bundle that passed its signature and hash checks means for this build. Separate from [load] so the
         * rules can be tested without a signed bundle.
         */
        @Suppress(
            "TooGenericExceptionCaught"
        ) // the parser's failures are many; any of them means the config is unusable
        internal fun interpret(
            manifest: BundleManifest,
            triageConfigText: String,
            isDevelopmentKey: Boolean,
            isDebuggable: Boolean,
            modelText: String = "",
            trustListText: String? = null
        ): VerifiedAssets {
            if (isDevelopmentKey && !isDebuggable) {
                return VerifiedAssets(IntegrityResult.DevelopmentKeyInRelease, null)
            }
            val config = try {
                TriageConfigParser.parse(triageConfigText)
            } catch (_: RuntimeException) {
                return VerifiedAssets(IntegrityResult.UnusableAsset(TRIAGE_CONFIG), null)
            }
            // An unreadable trust list leaves nothing to check a card against, so the bundle is unusable as a whole.
            val trustList = if (trustListText == null) {
                null
            } else {
                try {
                    TrustListParser.parse(trustListText)
                } catch (_: RuntimeException) {
                    return VerifiedAssets(IntegrityResult.UnusableAsset(TRUST_LIST), null)
                }
            }
            val modelVersion = MODEL_VERSION.find(modelText)?.groupValues?.get(1)
            return VerifiedAssets(IntegrityResult.Valid(manifest), config, modelVersion, trustList)
        }
    }
}
