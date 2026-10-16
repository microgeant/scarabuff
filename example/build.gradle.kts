plugins {
    kotlin("jvm") version "1.9.0"
    id("io.github.microgeant.scarabuff")
}

// Versions here must match gradle/libs.versions.toml in the parent project — this build is
// intentionally standalone (composite-built via `includeBuild("..")`, see settings.gradle.kts)
// so it doesn't share that catalog directly.
val flatbuffersJavaVersion = "25.2.10"
val flatbuffersJavaGrpcVersion = "2.0.3"
val grpcVersion = "1.70.0"
val grpcKotlinVersion = "1.2.1"
val coroutinesVersion = "1.7.3"

java {
    sourceCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("com.google.flatbuffers:flatbuffers-java:$flatbuffersJavaVersion")
    implementation("com.google.flatbuffers:flatbuffers-java-grpc:$flatbuffersJavaGrpcVersion")
    implementation("io.grpc:grpc-stub:$grpcVersion")
    implementation("io.grpc:grpc-netty:$grpcVersion")
    implementation("io.grpc:grpc-kotlin-stub:$grpcKotlinVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    implementation("javax.annotation:javax.annotation-api:1.3.2")

    testImplementation("io.grpc:grpc-inprocess:$grpcVersion")
    testImplementation("io.grpc:grpc-okhttp:$grpcVersion") // the Android transport, for StreamingBuilderReuseTest
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("com.google.truth:truth:1.4.0")
}

tasks.test {
    useJUnitPlatform()
}
