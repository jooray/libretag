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
