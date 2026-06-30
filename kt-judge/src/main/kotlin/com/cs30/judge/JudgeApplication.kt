package com.cs30.judge

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration
import org.springframework.boot.context.properties.ConfigurationPropertiesScan

// The judge server. Booted by the shared launcher when the `judge` profile is
// active; scans only com.cs30.judge and skips the DB autoconfig (judge needs no
// database). The backend's Application scans com.cs30.server, so the two roles
// share one jar without their beans ever mixing.
@SpringBootApplication(
    scanBasePackages = ["com.cs30.judge"],
    exclude = [DataSourceAutoConfiguration::class, HibernateJpaAutoConfiguration::class],
)
@ConfigurationPropertiesScan
class JudgeApplication
