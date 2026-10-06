plugins {
    alias(libs.plugins.fabric.loom)
}

dependencies {
    minecraft(libs.minecraft)
    implementation(libs.fabric.loader)
    implementation(libs.fabric.api)
    // Im Jar des Mods mitgeliefert (jar-in-jar), samt allem, was der WebP-Leser braucht.
    for (bibliothek in listOf(libs.twelvemonkeys.webp, libs.twelvemonkeys.core, libs.twelvemonkeys.metadata,
            libs.twelvemonkeys.lang, libs.twelvemonkeys.io, libs.twelvemonkeys.image)) {
        implementation(bibliothek)
        include(bibliothek)
    }

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

loom {
    accessWidenerPath = file("src/main/resources/heroicmap.accesswidener")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks.test {
    useJUnitPlatform()
}

// Die Lizenzen gehen mit dem Jar, auch der Hinweis, den TwelveMonkeys verlangt.
tasks.jar {
    from(files("LICENSE", "NOTICE")) {
        into("META-INF")
    }
}

// Bilder der Minimap: ./gradlew runClientGameTest -Pbilder=docs/bilder schreibt sie dorthin.
// Die Messung läuft nur mit -Pmessung=<datei>, siehe docs/minimap.md, „Kosten“.
fabricApi {
    configureTests {
        createSourceSet = true
        modId = "heroicmap-tests"
        enableGameTests = false
        enableClientGameTests = true
    }
}

val bilder = providers.gradleProperty("bilder").map { file(it).absolutePath }.orElse("")
val messung = providers.gradleProperty("messung").map { file(it).absolutePath }.orElse("")
tasks.matching { it.name == "runClientGameTest" }.configureEach {
    (this as JavaExec).systemProperty("heroicmap.bilder", bilder.get())
    systemProperty("heroicmap.messung", messung.get())
}

tasks.processResources {
    val version = project.version
    inputs.property("version", version)
    filesMatching("fabric.mod.json") {
        expand("version" to version)
    }
}
