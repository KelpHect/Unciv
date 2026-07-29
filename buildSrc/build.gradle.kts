plugins {
    `kotlin-dsl`
}

kotlin {
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.gdx.tools) {
        exclude("com.badlogicgames.gdx", "gdx-backend-lwjgl")
    }
}
