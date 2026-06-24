package com.cs30.server.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.web.util.matcher.IpAddressMatcher
import org.springframework.web.filter.OncePerRequestFilter

class IpWhitelistFilter(allowedEntries: List<String>) : OncePerRequestFilter() {

    private val matchers: List<IpAddressMatcher> = allowedEntries.map { IpAddressMatcher(it) }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain
    ) {
        if (matchers.isEmpty() || matchers.any { it.matches(request) }) {
            chain.doFilter(request, response)
            return
        }
        val clientIp = request.getHeader(X_FORWARDED_FOR)?.split(",")?.first()?.trim()
            ?: request.remoteAddr
        log.warn("Blocked request from IP: $clientIp")
        response.status = HttpServletResponse.SC_FORBIDDEN
        response.contentType = "application/json"
        response.writer.write("""{"error":"Access denied","ip":"$clientIp"}""")
    }

    companion object {
        private val log = LoggerFactory.getLogger(IpWhitelistFilter::class.java)
        private const val X_FORWARDED_FOR = "X-Forwarded-For"
    }
}
