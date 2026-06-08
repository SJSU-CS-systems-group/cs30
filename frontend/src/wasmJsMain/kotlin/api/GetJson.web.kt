package backend

import data.MockDataRepository

// Note: Web-based fetch of problems from backend not yet implemented.
// For now, use bundled resources like desktop debug mode.
// The authHeader is ignored since web uses session cookies.
actual suspend fun getJson(baseUrl: String, path: String, authHeader: String?): String =
    when {
        path.endsWith("/problems") -> """[]"""  // Empty problem list for now
        path.contains("/problems/") && path.endsWith(".html") ->
            MockDataRepository.getProblemHtml(path.substringBefore("/").substringAfterLast("/"))
        path.endsWith("/css") -> MockDataRepository.getProblemCss()
        else -> ""
    }
