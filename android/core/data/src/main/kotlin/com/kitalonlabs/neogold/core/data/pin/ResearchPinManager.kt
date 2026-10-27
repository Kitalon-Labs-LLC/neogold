@file:Suppress("MagicNumber") // lockout schedule: 5, 10 then 15 minutes

package com.kitalonlabs.neogold.core.data.pin

import com.kitalonlabs.neogold.core.data.local.dao.AuditEventDao
import com.kitalonlabs.neogold.core.data.local.dao.ResearchStudyDao
import com.kitalonlabs.neogold.core.data.local.entity.AuditEventEntity
import com.kitalonlabs.neogold.core.data.local.entity.ResearchStudyEntity
import com.kitalonlabs.neogold.core.security.pin.Argon2PinHasher
import com.kitalonlabs.neogold.core.security.pin.PinPolicyValidator
import com.kitalonlabs.neogold.core.security.pin.PinValidationResult
import java.util.UUID
import kotlin.math.ceil

sealed interface PinCheckResult {
    data object Success : PinCheckResult
    data class WrongPin(val failedAttempts: Int, val attemptsUntilNextLockout: Int) : PinCheckResult
    data class LockedOut(val lockedUntilMs: Long, val remainingMinutes: Int) : PinCheckResult
}

/**
 * Manages research mode PIN verification, pre-check failure counting,
 * progressive lockout enforcement, and audit event recording (doc 8 Section 6.2).
 */
class ResearchPinManager(
    private val studyDao: ResearchStudyDao,
    private val auditEventDao: AuditEventDao,
    private val pinHasher: Argon2PinHasher = Argon2PinHasher()
) {

    companion object {
        const val LOCKOUT_TIER_1_ATTEMPTS = 5
        const val LOCKOUT_TIER_2_ATTEMPTS = 10
        const val LOCKOUT_TIER_3_ATTEMPTS = 15

        const val LOCKOUT_1_MIN_MS = 60 * 1000L
        const val LOCKOUT_15_MIN_MS = 15 * 60 * 1000L
        const val LOCKOUT_1_HOUR_MS = 60 * 60 * 1000L
    }

    /**
     * Checks whether the given study is currently locked out.
     */
    fun isStudyLocked(study: ResearchStudyEntity, now: Long = System.currentTimeMillis()): Boolean {
        val lockedUntil = study.lockedUntil ?: return false
        return lockedUntil > now
    }

    /**
     * Returns remaining lockout minutes for a study, or 0 if not locked.
     */
    fun getRemainingLockoutMinutes(study: ResearchStudyEntity, now: Long = System.currentTimeMillis()): Int {
        val lockedUntil = study.lockedUntil ?: return 0
        if (lockedUntil <= now) return 0
        val remainingMs = lockedUntil - now
        return ceil(remainingMs.toDouble() / 60_000.0).toInt().coerceAtLeast(1)
    }

    /**
     * Verifies a candidate PIN against a study.
     * Enforces pre-check failure counter write to ensure resilience across process kill.
     */
    suspend fun verifyPin(
        studyId: String,
        candidatePin: String,
        now: Long = System.currentTimeMillis()
    ): PinCheckResult {
        val study = studyDao.getStudyById(studyId)
            ?: throw IllegalArgumentException("Study not found with id $studyId")

        // 1. Check existing lockout
        if (isStudyLocked(study, now)) {
            val lockedUntil = study.lockedUntil!!
            val remMins = getRemainingLockoutMinutes(study, now)
            return PinCheckResult.LockedOut(lockedUntil, remMins)
        }

        // 2. Pre-check write: Increment failure counter BEFORE evaluating the PIN
        val nextAttempts = study.failedPinAttempts + 1
        val lockoutDurationMs: Long? = when {
            nextAttempts >= LOCKOUT_TIER_3_ATTEMPTS -> LOCKOUT_1_HOUR_MS
            nextAttempts == LOCKOUT_TIER_2_ATTEMPTS -> LOCKOUT_15_MIN_MS
            nextAttempts == LOCKOUT_TIER_1_ATTEMPTS -> LOCKOUT_1_MIN_MS
            else -> null
        }
        val nextLockedUntil = lockoutDurationMs?.let { now + it }

        val preCheckStudy = study.copy(
            failedPinAttempts = nextAttempts,
            lockedUntil = nextLockedUntil
        )
        studyDao.update(preCheckStudy)

        // 3. Evaluate PIN hash
        val isMatch = pinHasher.verifyPin(candidatePin, study.pinSalt, study.pinHash)

        return if (isMatch) {
            // Success: Reset failure count and lockout
            val resetStudy = preCheckStudy.copy(
                failedPinAttempts = 0,
                lockedUntil = null
            )
            studyDao.update(resetStudy)

            auditEventDao.insert(
                AuditEventEntity(
                    id = UUID.randomUUID().toString(),
                    eventType = "STUDY_UNLOCKED",
                    occurredAt = now,
                    details = null
                )
            )
            PinCheckResult.Success
        } else {
            // Failure: Record audit event and return lockout / wrong pin result
            auditEventDao.insert(
                AuditEventEntity(
                    id = UUID.randomUUID().toString(),
                    eventType = "PIN_FAILED",
                    occurredAt = now,
                    details = null
                )
            )

            if (nextLockedUntil != null) {
                val remMins = ceil((nextLockedUntil - now).toDouble() / 60_000.0).toInt().coerceAtLeast(1)
                PinCheckResult.LockedOut(nextLockedUntil, remMins)
            } else {
                val nextThreshold = when {
                    nextAttempts < 5 -> 5
                    nextAttempts < 10 -> 10
                    else -> 15
                }
                val attemptsRemaining = nextThreshold - nextAttempts
                PinCheckResult.WrongPin(nextAttempts, attemptsRemaining)
            }
        }
    }

    /**
     * Sets or updates the PIN for a study after validating against PIN policy.
     */
    suspend fun setPin(
        studyId: String,
        pin: String
    ): Result<ResearchStudyEntity> {
        val validation = PinPolicyValidator.validate(pin)
        if (validation is PinValidationResult.Invalid) {
            return Result.failure(IllegalArgumentException("PIN does not satisfy security policy: ${validation.error}"))
        }

        val study = studyDao.getStudyById(studyId)
            ?: return Result.failure(IllegalArgumentException("Study $studyId not found"))

        val salt = pinHasher.generateSalt()
        val hash = pinHasher.hashPin(pin, salt)

        val updatedStudy = study.copy(
            pinHash = hash,
            pinSalt = salt,
            failedPinAttempts = 0,
            lockedUntil = null
        )
        studyDao.update(updatedStudy)
        return Result.success(updatedStudy)
    }
}
