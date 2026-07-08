package editor

import auth.ApiToken
import data.LabProblemInfo

actual fun createAutosaveService(
    baseUrl: String,
    problem: LabProblemInfo,
): AutosaveService = HttpAutosaveService(baseUrl, ApiToken.value?.let { "Bearer $it" }, problem)
