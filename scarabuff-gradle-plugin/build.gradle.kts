plugins {
    `java-gradle-plugin`
}

dependencies {
    implementation(project(":scarabuff-core"))
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.truth)
    testImplementation(gradleTestKit())
}

gradlePlugin {
    plugins {
        create("scarabuff") {
            id = "com.scarabuff.grpc"
            implementationClass = "com.scarabuff.gradle.ScarabuffPlugin"
            displayName = "scarabuff"
            description = "Generates FlatBuffers + gRPC Kotlin coroutine boilerplate from .fbs rpc_service definitions"
        }
    }
}
