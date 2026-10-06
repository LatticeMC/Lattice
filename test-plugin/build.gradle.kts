version = "1.0.0-SNAPSHOT"

repositories {
    // Kept on the isolated benchmark source set below. MCProtocolLib's
    // 1.21.11 release is published by GeyserMC here, not on Maven Central.
    maven("https://repo.opencollab.dev/main/")
}

val activationBench by sourceSets.creating {
    java.srcDir("src/activation-bench/java")
}

val mockitoAgent = configurations.register("mockitoAgent")
configurations.testImplementation {
    extendsFrom(configurations[activationBench.implementationConfigurationName])
}

dependencies {
    compileOnly(project(":lattice-api"))
    // This configuration belongs only to activationBench. It is deliberately
    // not a plugin implementation dependency and is never packaged in jar.
    add(activationBench.implementationConfigurationName, "org.geysermc.mcprotocollib:protocol:1.21.11-1")
    testImplementation(project(":lattice-api"))
    testImplementation(activationBench.output)
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testImplementation("org.mockito:mockito-core:5.14.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    mockitoAgent("org.mockito:mockito-core:5.14.1") { isTransitive = false }
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-javaagent:${mockitoAgent.get().singleFile.absolutePath}")
}

tasks.register<JavaExec>("runActivationBench") {
    group = "verification"
    description = "Runs the external MCProtocolLib activation-benchmark bot client"
    dependsOn(activationBench.classesTaskName)
    classpath = activationBench.runtimeClasspath
    mainClass.set("org.purpurmc.testplugin.activationbench.ActivationBenchBotRunner")
    standardInput = System.`in`
}

tasks.processResources {
    val apiVersion = rootProject.providers.gradleProperty("mcVersion").get()
    val props = mapOf(
        "version" to project.version,
        "apiversion" to "\"$apiVersion\"",
    )
    inputs.properties(props)
    filesMatching("paper-plugin.yml") {
        expand(props)
    }
}
