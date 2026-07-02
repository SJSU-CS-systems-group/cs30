package com.cs30.judge

import org.springframework.boot.web.server.ConfigurableWebServerFactory
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.stereotype.Component

// Binds the embedded server to judge.port. This runs after property binding and
// overrides any server.port in the shared application.properties, so the judge
// listens on its own port without needing a --server.port launch flag.
@Component
class JudgePortCustomizer(private val props: JudgeProperties) :
    WebServerFactoryCustomizer<ConfigurableWebServerFactory> {
    override fun customize(factory: ConfigurableWebServerFactory) {
        factory.setPort(props.port)
    }
}
