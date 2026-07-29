import com.unciv.build.BuildConfig
import org.gradle.api.tasks.testing.logging.TestLogEvent

// Java 21+ deprecates dynamic agent loading: https://openjdk.org/jeps/451
val mockitoAgent = configurations.create("mockitoAgent")

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.mockito)
    testRuntimeOnly(libs.logback)
    mockitoAgent(libs.mockito) { isTransitive = false }
}

tasks {
    test {
        workingDir = file("../android/assets")
        testLogging.lifecycle {
            events(
                    TestLogEvent.FAILED,
                    TestLogEvent.STANDARD_ERROR,
                    TestLogEvent.STANDARD_OUT
            )

            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }

        // Mockito's explicit agent disables class-data sharing, so turn it off deliberately
        // instead of making every test JVM print the bootstrap-classpath warning.
        jvmArgs = listOf("-Xshare:off", "-javaagent:${mockitoAgent.asPath}")
    }
}

sourceSets {
    test {
        java.srcDir("src")
    }
}

eclipse.project {
    name = "${BuildConfig.appName}-tests"
}
