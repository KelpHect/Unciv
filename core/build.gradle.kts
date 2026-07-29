import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("kotlin")
}

sourceSets {
    main {
        java.srcDir("src/")
        resources.srcDir(layout.buildDirectory.dir("generated/authoritativeV3Compatibility"))
    }
}

val generateAuthoritativeV3Compatibility by tasks.registering(Copy::class) {
    from(rootProject.file("authoritative-server/release/compatibility.json"))
    into(layout.buildDirectory.dir("generated/authoritativeV3Compatibility"))
    rename { "authoritative-v3-compatibility.json" }
}

tasks.named("processResources") {
    dependsOn(generateAuthoritativeV3Compatibility)
}


kotlin {
    compilerOptions {
        allWarningsAsErrors.set(true)
        jvmTarget = JvmTarget.JVM_1_8
    }
}

java {
    targetCompatibility = JavaVersion.VERSION_1_8
}
