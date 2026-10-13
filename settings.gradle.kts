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
        // karoo-ext is public, but GitHub Packages always requires authentication.
        // Put gpr.user / gpr.key in %USERPROFILE%\.gradle\gradle.properties (see CLAUDE.md).
        maven {
            url = uri("https://maven.pkg.github.com/hammerheadnav/karoo-ext")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GPR_USER")
                password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GPR_KEY")
            }
        }

        // karoo-ext-testing, test-only
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter { includeGroup("com.github.nikosavola.karoo-ext-testing") }
        }
    }
}

rootProject.name = "pinion-karoo-extension"
include(":app")
