plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":path-core-models"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    testImplementation(project(":path-mock-adapter"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

