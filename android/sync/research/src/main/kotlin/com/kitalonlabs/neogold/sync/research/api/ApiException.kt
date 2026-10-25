package com.kitalonlabs.neogold.sync.research.api

import com.kitalonlabs.neogold.sync.research.api.models.Problem
import okhttp3.Headers

class ApiException(
    val statusCode: Int,
    val problem: Problem,
    val headers: Headers? = null
) : Exception("API Error $statusCode: ${problem.code} - ${problem.title}. ${problem.detail ?: ""}") {
    val errorCode: String get() = problem.code
    val isRetryable: Boolean get() = problem.retryable
}
