dependencies {
    implementation(libs.kotlinpoet)
    // Reflection classes (com.google.flatbuffers.reflection) for reading flatc's .bfbs output
    implementation(libs.flatbuffers.java)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.truth)
}
