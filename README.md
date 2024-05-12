# Interjava

A WIP gradle plugin with the intention of implementing [jvmDowngrader](https://github.com/unimined/JvmDowngrader)
in a gradle environment in a way that is extensible and following the design paradigms
set here at stianloader.

This plugin is currently in the "is the idea even good?" phase and as such
the documentation is currently still to be drafted. As such consider not using
the plugin for the time being until we can verify that the plugin can be used
as-is without further issues. 

## Maven

The plugin is available under our maven repository: https://stianloader.org/maven

Beware that we use a not-so-traditional versioning scheme, so ensure you
are using the proper version.

## Examples

Note that at this point in time all examples make use of groovy gradle.
This is largely caused by groovy being the golden standard here in stianloader
as it is the most widely supported gradle DSL for IDEs.

### Including the plugin

build.gradle:

```groovy
plugins {
    // [...]

    id 'org.stianloader.interjava' version '0.1.0-a20240512'
}
```

settings.gradle:

```groovy
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()

        // [...]

        maven {
            name = 'stianloader-maven'
            url = 'https://stianloader.org/maven/'
        }
        maven {
            name = "wagyourtail"
            url = "https://maven.wagyourtail.xyz/releases"
        }
    }
}

```

### Creating a downgraded jar of the `jar` task

```groovy
repositories {
    // [...]
    maven {
        name = 'wagyourtail'
        url = 'https://maven.wagyourtail.xyz/releases'
    }
}

configurations {
    jvmDowngraderJavaApi
}

dependencies {
    // [...]

    // Java 8 APIs required by jvmDowngrader
    jvmDowngraderJavaApi (group: 'xyz.wagyourtail.jvmdowngrader', name: 'jvmdowngrader-java-api', version: '0.1.2', classifier: 'downgraded-8')
}

// Note: DowngradedArchiveTask is an AbstractArchiveTask and as such has all the methods and properties AbstractArchiveTasks normally have
task downgradedJar(type: org.stianloader.interjava.DowngradedArchiveTask, dependsOn: jar) {
    from(zipTree(jar.archiveFile))
    archiveVersion = jar.archiveVersion
    archiveBaseName = jar.archiveBaseName

    targetVersion = 8

    // The following is only required to recompute frames. So all direct compile-time dependencies need to be present.
    sourceSets.main.compileClasspath.each { classpathEntry ->
        compileClassPath.from(zipTree(classpathEntry))
    }
}

build {
    dependsOn(downgradedJar)
}
```

Note that `jvmDowngraderJavaApi` could also be `runtime`, in which case you don't need to
include the `configurations` block. However, using a dedicated configuration to store the
jvmdowngrader-java-api artifact will be useful when making use of variant publishing.

### Variant publishing

Defining the downgraded configuration that needs to be published:

```groovy
configurations {
    downgradedJar {
        canBeConsumed = true
        canBeResolved = false
        attributes {
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category, Category.LIBRARY))
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, Usage.JAVA_RUNTIME))
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling, Bundling.EXTERNAL))
            attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 8)
            attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements, 'jar'))
        }
        artifacts {
            downgradedJar
        }

        outgoing {
            artifact tasks['downgradedJar']
        }

        extendsFrom configurations['implementation']
        extendsFrom configurations['jvmDowngraderJavaApi']
    }
}
```

**Tip:** The order of blocks matter: This configuration block needs to be below
the `downgradedJar` task definition, which needs to be below the `dependencies` definition which
in turn needs to be below the `configurations` block defining `jvmDowngraderJavaApi`.
Thankfully, we can have two `configurations` blocks.

Defining the downgraded configuration as a variant (needs to be after the second `configurations` block):

```groovy
components {
    java {
        addVariantsFromConfiguration(configurations['downgradedJar']) {
            mapToMavenScope("runtime")
        }
    }
}
```

Actually publishing the variant (should be after the `components` block):
```groovy
publishing {
    publications {
        plugin(MavenPublication) { publication ->
            // [...]

            from components['java']
        }
    }

    // [...]
}

publish {
    dependsOn(downgradedJar)
}
```

Note that `from components['java']` will cause all other variants associated
with the `java` component to be published. Unfortunately, one cannot create components
out of thin air in the buildscript without using a plugin for it.
See: https://docs.gradle.org/current/userguide/publishing_customization.html#sec:publishing-custom-components

### Consuming downgraded variants

```groovy
repositories {
    // [...]
    maven {
        name = 'wagyourtail'
        url = 'https://maven.wagyourtail.xyz/releases'
    }
}

dependencies {
    implementation (group: 'org.stianloader', name: 'jlsl', version: '1.0.0', classifier: 'j8')
}
```

There isn't really anything special here to be honest. The above
code should be familiar enough if you have ever worked with classifiers
before.

The only thing you need to be aware of is that you cannot remove the transitive
dependency on `jvmdowngrader-java-api`, which is why it is important to define
the repository of jvmdowngrader.

While it is planned on supporting the ability to shade `jvmdowngrader-java-api`
into the produced jar, the ability to do so has not yet been added.

## Performance

The performance of this plugin is likely going to be pretty bad with larger jars.
It might be worse than jvmDowngrader's official plugin for any jar in any environment in fact.

However, performance is only rarely our selling point when reinventing the wheel.
What else did you expect.
