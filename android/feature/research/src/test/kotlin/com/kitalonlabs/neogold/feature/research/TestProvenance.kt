package com.kitalonlabs.neogold.feature.research

import com.kitalonlabs.neogold.feature.research.data.ResearchProvenance

/** The provenance the research tests save sessions with: a fixed app, model and config, and no device profile. */
internal val TEST_PROVENANCE = ResearchProvenance(
    appVersion = "1.0.0-test",
    modelVersion = "model-test",
    configVersion = "config-test",
    deviceProfileId = null
)
