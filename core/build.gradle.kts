plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // org.json zit in Android zelf; de server voegt hem zelf toe
    compileOnly("org.json:json:20240303")
    // DTLS voor de Hue Entertainment-stream (Android heeft zelf geen DTLS met PSK)
    api("org.bouncycastle:bctls-jdk18on:1.78.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
