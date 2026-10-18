package com.kitalonlabs.neogold.engine.estimation

/**
 * Root class for all typed estimation engine exceptions.
 */
sealed class EstimationException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause) {

    /**
     * Model file was missing or could not be found.
     */
    class ModelNotFoundException(message: String) : EstimationException(message)

    /**
     * Model JSON failed syntax, schema, or semantic validation.
     */
    class InvalidModelException(
        val reason: Reason,
        message: String,
        cause: Throwable? = null
    ) : EstimationException(message, cause) {

        enum class Reason {
            SYNTAX_ERROR,
            SCHEMA_VIOLATION,
            MISSING_REQUIRED_FIELD,
            UNSUPPORTED_VERSION,
            DIMENSION_MISMATCH,
            INVALID_SCALE,
            INVALID_QUANTILE,
            INVALID_WEIGHTS,
            UNKNOWN_FEATURE
        }
    }

    /**
     * Insufficient or missing body site captures were supplied.
     */
    class MissingSiteDataException(message: String) : EstimationException(message)

    /**
     * General computation or estimation failure.
     */
    class EstimationFailedException(message: String, cause: Throwable? = null) : EstimationException(message, cause)
}

/**
 * Typed outcome of an estimation operation (Success or Failure).
 */
sealed interface EstimationResult {
    /**
     * Estimation completed successfully.
     */
    data class Success(val output: EstimationOutput) : EstimationResult

    /**
     * Estimation failed due to missing/invalid model or invalid inputs.
     * Callers should disable estimation and route to blood test recommendation (TC-EST-06).
     */
    data class Failure(val error: EstimationException) : EstimationResult
}
