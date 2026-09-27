plugins {
    id("java-library")
    id("com.gradleup.shadow") version "9.6.1"
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

repositories {
    mavenCentral()
    // Also carries PlotSquared's releases.
    maven("https://repo.papermc.io/repository/maven-public/")
    // SimpleCloud's own API artifact and its Buf-generated protobuf dependency.
    maven("https://repo.simplecloud.app/snapshots")
    maven("https://buf.build/gen/maven")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    // Only referenced by the sync-mode classes, which are never instantiated on a server that doesn't
    // run PlotSquared - so a missing PlotSquared jar at runtime elsewhere is harmless (lazy class loading).
    compileOnly("com.intellectualsites.plotsquared:plotsquared-core:7.6.0")
    compileOnly("com.intellectualsites.plotsquared:plotsquared-bukkit:7.6.0")
    // Optional: used only to auto-detect this server's own SimpleCloud name (ServerIdentity falls back
    // to config/ENV/hostname if the simplecloud-api platform plugin isn't present). Do not shade this -
    // that plugin provides it at runtime, and bundling a second copy would cause class-loading conflicts.
    compileOnly("app.simplecloud.api:api:0.1.0-platform.58-dev.5.1-fe01ce5")

    implementation("redis.clients:jedis:7.5.3")
    implementation("com.zaxxer:HikariCP:6.2.1")
    implementation("com.mysql:mysql-connector-j:9.1.0")

    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

tasks {
    runServer {
        // Configure the Minecraft version for our task.
        // This is the only required configuration besides applying the plugin.
        // Your plugin's jar (or shadowJar if present) will be used automatically.
        minecraftVersion("1.21.11")
        jvmArgs("-Xms2G", "-Xmx2G")
    }

    processResources {
        // Gradle would otherwise write plugin.yml in the platform charset, which Paper rejects if it is not UTF-8.
        filteringCharset = "UTF-8"
        val props = mapOf("version" to version, "description" to project.description)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }

    test {
        useJUnitPlatform()
    }

    // The plain jar and shadowJar both default to the same output filename once shadowJar's classifier
    // is cleared below; with both enabled, whichever finishes last wins the race and overwrites the
    // other's output non-deterministically. Only the shaded jar is a usable plugin artifact, so it's
    // the only one that should exist.
    jar {
        enabled = false
    }

    shadowJar {
        archiveClassifier.set("")
        val libs = "net.clanimg.plotsGUI.libs"
        relocate("redis.clients", "$libs.redis")
        relocate("org.apache.commons.pool2", "$libs.pool2")
        relocate("com.zaxxer.hikari", "$libs.hikari")
        relocate("com.mysql", "$libs.mysql")
        relocate("com.google.protobuf", "$libs.protobuf")
        // Paper ships slf4j; bundling a second copy would clash with the server's.
        dependencies {
            exclude(dependency("org.slf4j:.*"))
        }
    }

    build {
        dependsOn(shadowJar)
    }
}
