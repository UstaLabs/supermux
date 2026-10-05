// Build-time helpers shared with :desktop's tests. Plain Java on purpose: no Kotlin plugin here,
// so nothing lands on the build classpath that could clash with the Kotlin Gradle plugin the
// modules request. The sources live with the module they serve (desktop/packaging/src) and
// :desktop's test source set compiles them too, so `:desktop:test` covers them.
plugins {
    java
}

sourceSets {
    main {
        java.srcDir("../desktop/packaging/src")
    }
}
