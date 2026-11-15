plugins {
    `java-library`
}

dependencies {
    api("com.google.code.gson:gson:2.11.0")
    implementation("org.slf4j:slf4j-api:2.0.16")

    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.16")
}
