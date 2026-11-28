pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // MeshCore Local API client (github.com/jooray/meshcore-local-api)
        maven("https://jitpack.io") { content { includeGroup("com.github.jooray") } }
    }
}

rootProject.name = "LibreTag"
include(":app")

// MeshCore Local API client (github.com/jooray/meshcore-local-api), pinned to one commit by
// `meshcoreLocalApi.commit` in gradle.properties. When a checkout of that repository sits next to this
// one (`meshcoreLocalApi.dir`, default ../../meshcore-local-api), its kotlin/ build is included and
// replaces the dependency (a composite build: nothing is published, no network). The checkout must be
// at the pinned commit with a clean kotlin/ tree, or the build stops; -PmeshcoreLocalApi.unpinned=true
// allows other states for a development build, never for a release. Without a checkout the same commit
// comes from JitPack. Either way the APK is built from the same library source.
val mclaCommit: String = providers.gradleProperty("meshcoreLocalApi.commit").get()
val mclaDir = File(settingsDir, providers.gradleProperty("meshcoreLocalApi.dir").getOrElse("../../meshcore-local-api"))
if (File(mclaDir, "kotlin/settings.gradle.kts").isFile) {
    fun git(vararg args: String): String {
        val p = ProcessBuilder(listOf("git", "-C", mclaDir.path) + args).redirectErrorStream(true).start()
        return p.inputStream.bufferedReader().readText().trim().also { p.waitFor() }
    }
    val head = git("rev-parse", "HEAD")
    val dirty = git("status", "--porcelain", "--", "kotlin")
    if ((head != mclaCommit || dirty.isNotEmpty()) && providers.gradleProperty("meshcoreLocalApi.unpinned").orNull != "true") {
        throw GradleException("meshcore-local-api at $mclaDir is at $head" + (if (dirty.isEmpty()) "" else " with changes in kotlin/") +
            ", gradle.properties pins $mclaCommit: check out that commit, or build with -PmeshcoreLocalApi.unpinned=true")
    }
    includeBuild(File(mclaDir, "kotlin"))
}
