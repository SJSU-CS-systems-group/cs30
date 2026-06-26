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
        // With server.forward-headers-strategy=native, request.remoteAddr is already
        // the real client IP resolved from X-Forwarded-For by Tomcat.
        if (matchers.isEmpty() || matchers.any { it.matches(request) }) {
            chain.doFilter(request, response)
            return
        }
        val clientIp = request.remoteAddr
        log.warn("Blocked request from IP: $clientIp")
        response.status = HttpServletResponse.SC_FORBIDDEN
        response.contentType = "text/html;charset=UTF-8"
        response.writer.write(blockedPage(clientIp))
    }

    companion object {
        private val log = LoggerFactory.getLogger(IpWhitelistFilter::class.java)

        private fun blockedPage(ip: String) = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>Access Restricted — CS30</title>
              <style>
                * { box-sizing: border-box; margin: 0; padding: 0; }
                body {
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                  background: #1e1e1e; color: #d4d4d4;
                  display: flex; align-items: center; justify-content: center;
                  min-height: 100vh;
                }
                .card {
                  background: #252526; border: 1px solid #3c3c3c;
                  border-radius: 8px; padding: 40px 48px; max-width: 480px; width: 90%;
                  text-align: center;
                }
                .icon { font-size: 48px; margin-bottom: 20px; }
                h1 { font-size: 20px; font-weight: 600; color: #ffffff; margin-bottom: 12px; }
                p { font-size: 14px; line-height: 1.6; color: #9d9d9d; margin-bottom: 8px; }
                .ip {
                  display: inline-block; margin-top: 20px;
                  background: #2d2d2d; border: 1px solid #3c3c3c;
                  border-radius: 4px; padding: 6px 14px;
                  font-family: monospace; font-size: 13px; color: #ce9178;
                }
              </style>
            </head>
            <body>
              <div class="card">
                <div class="icon">🔒</div>
                <h1>Access Restricted</h1>
                <p>CS30 is only accessible from authorized lab networks.</p>
                <p>Please connect to the correct network and try again, or contact your instructor if you believe this is an error.</p>
                <div class="ip">Your IP: $ip</div>
              </div>
            </body>
            </html>
        """.trimIndent()
    }
}
