package com.kitalonlabs.neogold

import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.core.data.local.entity.CardBatchEntity
import com.kitalonlabs.neogold.engine.cardtrust.VerificationResult
import com.kitalonlabs.neogold.feature.research.data.CardTrustStatus
import org.junit.Test

/**
 * Which card a research session works with (TC-CARD-05, safety review of C2): the card of the active profile is the one
 * named in the record, even when it has expired; without one, an unexpired card beats an expired one; a card whose
 * signature did not verify is never chosen; and a card counts as valid through the whole day it expires on, as the card
 * trust verifier counts it.
 */
class ChooseResearchCardTest {

    private val day = 86_400_000L
    private val now = 100 * day + 5_000L // five seconds into day 100

    private fun batch(id: String, signatureValid: Int, expiresAt: Long, firstSeenAt: Long = 0) = CardBatchEntity(
        batchId = id,
        issuerId = "issuer",
        formatVersion = 1,
        referenceValues = "[]",
        issuedAt = 0L,
        expiresAt = expiresAt,
        signatureValid = signatureValid,
        qrPayload = id.toByteArray(),
        serverStatus = "NOT_SENT",
        firstSeenAt = firstSeenAt
    )

    @Test
    fun theProfileCardIsNamedEvenWhenItHasExpired_NotADifferentValidCard_TC_CARD_05() {
        val profile = batch("profile", signatureValid = 1, expiresAt = now - 10 * day)
        val otherValid = batch("other", signatureValid = 1, expiresAt = now + 10 * day)

        val chosen = chooseResearchCard(profile, listOf(otherValid), now)

        assertThat(chosen?.batchId).isEqualTo("profile")
        assertThat(cardHasExpired(chosen!!, now)).isTrue()
    }

    @Test
    fun withoutAProfileCardAnUnexpiredCardBeatsANewerExpiredOne_TC_CARD_05() {
        val newerExpired = batch("expired", signatureValid = 1, expiresAt = now - 2 * day)
        val olderValid = batch("valid", signatureValid = 1, expiresAt = now + day)

        assertThat(chooseResearchCard(null, listOf(newerExpired, olderValid), now)?.batchId).isEqualTo("valid")
    }

    @Test
    fun whenEveryCardHasExpiredTheFirstIsKeptSoTheRecordSaysExpired_TC_CARD_05() {
        val first = batch("first", signatureValid = 1, expiresAt = now - 2 * day)
        val second = batch("second", signatureValid = 1, expiresAt = now - 5 * day)

        assertThat(chooseResearchCard(null, listOf(first, second), now)?.batchId).isEqualTo("first")
    }

    @Test
    fun aCardWhoseSignatureDidNotVerifyIsNeverChosen_TC_CARD_02() {
        val unverified = batch("unverified", signatureValid = 0, expiresAt = now + 100 * day)

        assertThat(chooseResearchCard(unverified, listOf(unverified), now)).isNull()
        val signed = batch("ok", signatureValid = 1, expiresAt = now - 2 * day)
        assertThat(chooseResearchCard(unverified, listOf(unverified, signed), now)?.batchId).isEqualTo("ok")
    }

    @Test
    fun noCardsMeansNoChoice() {
        assertThat(chooseResearchCard(null, emptyList(), now)).isNull()
    }

    @Test
    fun aCardIsValidThroughTheWholeDayItExpiresOn_AsTheVerifierCountsIt_TC_CARD_05() {
        val startOfExpiryDay = batch("edge", signatureValid = 1, expiresAt = 100 * day)

        assertThat(cardHasExpired(startOfExpiryDay, 100 * day)).isFalse() // the start of its last valid day
        assertThat(cardHasExpired(startOfExpiryDay, 101 * day - 1)).isFalse() // the last millisecond of that day
        assertThat(cardHasExpired(startOfExpiryDay, 101 * day)).isTrue() // the next day
    }

    @Test
    fun r02AndTheRecordMapTheVerifiersAnswerTheSameWay_TC_CARD_05() {
        assertThat(cardStatusOf(VerificationResult.Verified(Unit))).isEqualTo(CardTrustStatus.VERIFIED)
        assertThat(cardStatusOf(VerificationResult.Expired)).isEqualTo(CardTrustStatus.EXPIRED)
        // a card that verified when it was scanned but whose issuer key was revoked since is not verified now
        assertThat(cardStatusOf(VerificationResult.Revoked)).isEqualTo(CardTrustStatus.UNVERIFIED)
        assertThat(cardStatusOf(VerificationResult.UnknownIssuer)).isEqualTo(CardTrustStatus.UNVERIFIED)
        assertThat(cardStatusOf(VerificationResult.BadSignature)).isEqualTo(CardTrustStatus.UNVERIFIED)
        assertThat(cardStatusOf(VerificationResult.WrongPurpose)).isEqualTo(CardTrustStatus.UNVERIFIED)
        assertThat(cardStatusOf(VerificationResult.Malformed)).isEqualTo(CardTrustStatus.UNVERIFIED)
    }
}
