import java.security.MessageDigest

plugins {
    alias(libs.plugins.fabric.loom)
}

// Sodium nur für die Gametests mit -Psodium, BetterF3 samt Cloth Config mit -Pbetterf3, aus dem Maven von Modrinth
// und von dort nichts sonst. Siehe docs/entscheidungen/0017-gametests-in-der-ci-auch-mit-sodium.md und docs/minimap.md, „Bei F3“.
repositories {
    exclusiveContent {
        forRepository { maven("https://api.modrinth.com/maven") { name = "Modrinth" } }
        filter { includeGroup("maven.modrinth") }
    }
}
val sodium = configurations.create("sodium") { isTransitive = false }
val betterf3 = configurations.create("betterf3") { isTransitive = false }

dependencies {
    sodium(libs.sodium)
    betterf3(libs.betterf3)
    betterf3(libs.cloth.config)
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
// Kacheln der Vollbildkarte nur mit -Puebernahme=<datei>, siehe docs/vollbildkarte.md, „Kacheln“; die Stufen der
// eigenen Karte als Bilder nur mit -PselbstZoom=<ordner>, siehe docs/selbst.md, „Pyramide“.
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
val selbstZoom = providers.gradleProperty("selbstZoom").map { file(it).absolutePath }.orElse("")
val server = providers.gradleProperty("server").orElse("")
val mitSodium = providers.gradleProperty("sodium").isPresent
val mitBetterF3 = providers.gradleProperty("betterf3").isPresent
val zusatzJars = files().apply {
    if (mitSodium) from(sodium)
    if (mitBetterF3) from(betterf3)
}
val zusatzSha256 = buildSet {
    if (mitSodium) add(libs.versions.sodium.sha256.get())
    if (mitBetterF3) addAll(listOf(libs.versions.betterf3.sha256.get(), libs.versions.cloth.config.sha256.get()))
}
tasks.matching { it.name == "runClientGameTest" }.configureEach {
    (this as JavaExec).systemProperty("heroicmap.bilder", bilder.get())
    systemProperty("heroicmap.messung", messung.get())
    systemProperty("heroicmap.messung.drehen", providers.gradleProperty("messungDrehen").orElse("false").get())
    systemProperty("heroicmap.messung.rahmen", providers.gradleProperty("messungRahmen").orElse("ohne").get())
    systemProperty("heroicmap.messung.effekte", providers.gradleProperty("messungEffekte").orElse("false").get())
    // true misst 0,5 gegen 2, eine Stufe wie 0.03125 sie gegen 2.
    systemProperty("heroicmap.messung.zoom", providers.gradleProperty("messungZoom").orElse("false").get())
    systemProperty("heroicmap.messung.regionen", providers.gradleProperty("messungRegionen").orElse("47").get())
    systemProperty("heroicmap.uebernahme", uebernahme.get())
    systemProperty("heroicmap.selbstzoom", selbstZoom.get())
    systemProperty("heroicmap.server", server.get())
    // Die Gametests fragen Modrinth nie; den Hinweis prüfen sie gegen einen Server auf loopback.
    systemProperty("heroicmap.ohne_modrinth", "true")
    val zusatz = providers.gradleProperty("zusatzmods").orNull?.let { file(it).absolutePath }
    zusatz?.let { systemProperty("fabric.addMods", it) }
    if (mitSodium || mitBetterF3) {
        // Lokale Kopien: Die Aktion darf das Skript nicht festhalten, sonst geht der Configuration Cache nicht.
        val jars = zusatzJars
        val soll = zusatzSha256
        // Erst die Prüfsummen, dann ins Spiel: Ein anderes Jar unter derselben Version bricht den Lauf ab.
        doFirst {
            val pfade = jars.files.map { jar ->
                val sha = MessageDigest.getInstance("SHA-256").digest(jar.readBytes()).joinToString("") { "%02x".format(it) }
                check(sha in soll) { "${jar.name}: SHA-256 $sha steht nicht in libs.versions.toml" }
                jar.absolutePath
            }
            (this as JavaExec).systemProperty("fabric.addMods", (listOfNotNull(zusatz) + pfade).joinToString(File.pathSeparator))
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
