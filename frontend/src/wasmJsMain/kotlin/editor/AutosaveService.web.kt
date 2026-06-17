package editor

import data.LabProblemInfo

actual fun createAutosaveService(
    baseUrl: String,
    problem: LabProblemInfo,
): AutosaveService = HttpAutosaveService(baseUrl, null, problem)
