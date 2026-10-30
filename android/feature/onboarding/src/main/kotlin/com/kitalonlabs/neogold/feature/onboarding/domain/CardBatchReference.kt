package com.kitalonlabs.neogold.feature.onboarding.domain

import com.kitalonlabs.neogold.engine.cardtrust.CardBatchPayload
import com.kitalonlabs.neogold.engine.colorscience.Lab
import com.kitalonlabs.neogold.engine.vision.CardGeometry

/**
 * The measured colors of a card batch as the `card_batch.reference_values` column keeps them (doc 5 Section 2.2): a JSON
 * array with one `[L, a, b]` entry per patch, in the card's canonical patch order (card/spec/card-v1.json).
 */
object CardBatchReference {

    private val patchIds: List<String> = CardGeometry.PATCHES.keys.toList()
    private val number = Regex("-?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?")

    /** Null when the batch does not give a value for every patch exactly once. */
    fun toJson(batch: CardBatchPayload): String? = batch.referenceValuesJson()

    /** The reference color of each patch by its ID, or null when the stored text is not a full set of 23. */
    fun fromJson(json: String): Map<String, Lab>? {
        val values = number.findAll(json).map { it.value.toDouble() }.toList()
        if (values.size != patchIds.size * 3) return null
        return patchIds.mapIndexed { i, id -> id to Lab(values[3 * i], values[3 * i + 1], values[3 * i + 2]) }.toMap()
    }
}
