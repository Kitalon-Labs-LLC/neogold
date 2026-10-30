package com.kitalonlabs.neogold

import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.core.data.local.entity.CardBatchEntity
import org.junit.Test

/**
 * Which card a research session works with (TC-CARD-05, safety review of C2 part 1): a card that has expired still goes
 * into the record, but it never beats a card that has not, and a card whose signature did not verify is never chosen.
 */
class ChooseResearchCardTest {

    private val now = 1_000_000L

    private fun batch(id: String, signatureValid: Int, expiresAt: Long, firstSeenAt: Long) = CardBatchEntity(
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
    fun aCardThatHasNotExpiredBeatsANewerOneThatHas_TC_CARD_05() {
        val older = batch("valid", signatureValid = 1, expiresAt = now + 1, firstSeenAt = 1)
        val newerExpired = batch("expired", signatureValid = 1, expiresAt = now - 1, firstSeenAt = 2)

        assertThat(chooseResearchCard(listOf(newerExpired, older), now)?.batchId).isEqualTo("valid")
    }

    @Test
    fun whenEveryCardHasExpiredTheFirstIsKeptSoTheRecordSaysExpired_TC_CARD_05() {
        val first = batch("first", signatureValid = 1, expiresAt = now - 1, firstSeenAt = 2)
        val second = batch("second", signatureValid = 1, expiresAt = now - 5, firstSeenAt = 1)

        assertThat(chooseResearchCard(listOf(first, second), now)?.batchId).isEqualTo("first")
    }

    @Test
    fun aCardWhoseSignatureDidNotVerifyIsNeverChosen_TC_CARD_02() {
        val unverified = batch("unverified", signatureValid = 0, expiresAt = now + 100, firstSeenAt = 3)

        assertThat(chooseResearchCard(listOf(unverified), now)).isNull()
        assertThat(
            chooseResearchCard(
                listOf(unverified, batch("ok", signatureValid = 1, expiresAt = now - 1, firstSeenAt = 1)),
                now
            )
                ?.batchId
        ).isEqualTo("ok")
    }

    @Test
    fun noCardsMeansNoChoice() {
        assertThat(chooseResearchCard(emptyList(), now)).isNull()
    }

    @Test
    fun aCardExpiringExactlyNowHasExpired() {
        val onTheDot = batch("edge", signatureValid = 1, expiresAt = now, firstSeenAt = 1)
        val later = batch("later", signatureValid = 1, expiresAt = now + 1, firstSeenAt = 0)

        assertThat(chooseResearchCard(listOf(onTheDot, later), now)?.batchId).isEqualTo("later")
    }
}
