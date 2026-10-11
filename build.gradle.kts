import java.security.MessageDigest

plugins {
    alias(libs.plugins.fabric.loom)
}

// Sodium nur für die Gametests mit -Psodium, aus dem Maven von Modrinth und von dort nichts sonst.
// Siehe docs/entscheidungen/0017-gametests-in-der-ci-auch-mit-sodium.md.
repositories {
    exclusiveContent {
        forRepository { maven("https://api.modrinth.com/maven") { name = "Modrinth" } }
        filter { includeGroup("maven.modrinth") }
    }
}
val sodium = configurations.create("sodium") { isTransitive = false }

dependencies {
    sodium(libs.sodium)
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

// Die Messung von Kachelwerk.schreibe läuft nur mit -Pkachelwerk=<datei>, siehe docs/selbst.md, „Kosten“.
val kachelwerk = providers.gradleProperty("kachelwerk").map { file(it).absolutePath }.orElse("")
tasks.test {
    useJUnitPlatform()
    systemProperty("heroicmap.kachelwerk", kachelwerk.get())
}

// Die Lizenzen gehen mit dem Jar, auch der Hinweis, den TwelveMonkeys verlangt.
tasks.jar {
    from(files("LICENSE", "NOTICE")) {
        into("META-INF")
    }
}

// Bilder der Minimap: ./gradlew runClientGameTest -Pbilder=docs/bilder schreibt sie dorthin.
// Die Messung läuft nur mit -Pmessung=<datei>, siehe docs/minimap.md, „Kosten“; die Übernahme der
// Kacheln der Vollbildkarte nur mit -Puebernahme=<datei>, siehe docs/vollbildkarte.md, „Kacheln“.
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
val uebernahme = providers.gradleProperty("uebernahme").map { file(it).absolutePath }.orElse("")
val server = providers.gradleProperty("server").orElse("")
val mitSodium = providers.gradleProperty("sodium").isPresent
val sodiumJar = files(sodium)
val sodiumSha256 = libs.versions.sodium.sha256.get()
tasks.matching { it.name == "runClientGameTest" }.configureEach {
    (this as JavaExec).systemProperty("heroicmap.bilder", bilder.get())
    systemProperty("heroicmap.messung", messung.get())
    systemProperty("heroicmap.messung.drehen", providers.gradleProperty("messungDrehen").orElse("false").get())
    systemProperty("heroicmap.messung.rahmen", providers.gradleProperty("messungRahmen").orElse("ohne").get())
    systemProperty("heroicmap.uebernahme", uebernahme.get())
    systemProperty("heroicmap.server", server.get())
    val zusatz = providers.gradleProperty("zusatzmods").orNull?.let { file(it).absolutePath }
    zusatz?.let { systemProperty("fabric.addMods", it) }
    if (mitSodium) {
        // Lokale Kopien: Die Aktion darf das Skript nicht festhalten, sonst geht der Configuration Cache nicht.
        val jars = sodiumJar
        val soll = sodiumSha256
        // Erst die Prüfsumme, dann ins Spiel: Ein anderes Jar unter derselben Version bricht den Lauf ab.
        doFirst {
            val jar = jars.singleFile
            val sha = MessageDigest.getInstance("SHA-256").digest(jar.readBytes()).joinToString("") { "%02x".format(it) }
            check(sha == soll) { "${jar.name}: SHA-256 $sha statt $soll" }
            (this as JavaExec).systemProperty("fabric.addMods", listOfNotNull(zusatz, jar.absolutePath).joinToString(File.pathSeparator))
        }
    }
    // Der Server-Fall von Blockentities nur mit einer eula.txt, die ein Mensch angenommen hat; der Test kopiert sie unverändert.
    systemProperty("heroicmap.eula", providers.gradleProperty("eula").map { file(it).absolutePath }.orElse("").get())
}

tasks.processResources {
    val version = project.version
    inputs.property("version", version)
    filesMatching("fabric.mod.json") {
        expand("version" to version)
    }
}
