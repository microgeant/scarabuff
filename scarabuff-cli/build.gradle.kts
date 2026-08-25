plugins {
    application
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":scarabuff-core"))
}

application {
    mainClass.set("com.scarabuff.cli.MainKt")
}
