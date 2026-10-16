plugins {
    kotlin("jvm") version "2.2.10"
    id("com.google.protobuf") version "0.9.5"
    id("io.github.microgeant.scarabuff")
}

// FlatBuffers (scarabuff) against Protocol Buffers on the same data and transport, with JMH. The
// build is standalone, like example/: it uses this checkout of scarabuff through
// `includeBuild("..")`, so results reflect the code next to it. gRPC matches
// gradle/libs.versions.toml in the parent project. It runs on Gradle 9 (example/ stays on the
// oldest supported Gradle), so CI also checks that a Gradle 9 build can include this checkout.
val grpcVersion = "1.70.0"
val protobufVersion = "3.25.5"
val jmhVersion = "1.37"

kotlin { jvmToolchain(17) }

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:$protobufVersion" }
}

dependencies {
    implementation("io.grpc:grpc-protobuf:$grpcVersion")
    implementation("com.google.protobuf:protobuf-java:$protobufVersion")
    implementation("io.grpc:grpc-netty:$grpcVersion")

    implementation("org.openjdk.jmh:jmh-core:$jmhVersion")
    annotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:$jmhVersion")
}

// ./gradlew jmh [-PjmhArgs="-p items=1000 HotSpotsBenchmark.enc"]: extra JMH arguments; the
// default runs everything. Results go to build/jmh-result.json, with allocation per operation
// (`-prof gc`).
tasks.register<JavaExec>("jmh") {
    group = "benchmark"
    description = "Runs the JMH benchmarks."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.openjdk.jmh.Main")
    val extra = (findProperty("jmhArgs") as String?)?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
    args(listOf("-prof", "gc", "-rf", "json", "-rff", layout.buildDirectory.file("jmh-result.json").get().asFile.absolutePath) + extra)
}
