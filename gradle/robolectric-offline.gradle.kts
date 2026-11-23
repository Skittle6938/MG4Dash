// Robolectric's Android jars resolved by Gradle (kept in its cache, and in CI's) instead of downloaded by
// Robolectric at test time: Maven Central answered 429 (too many requests) to those runtime downloads, and
// every test of a module then failed (23/11). The tests run offline from the copied jars.
// The SDK of the tests (@Config: sdk 28), and the latest one, which Robolectric also loads. One configuration
// each: in a single one, Gradle would keep only the highest version of the module.
val robolectricVersions = listOf("9-robolectric-4913185-2-i7", "15-robolectric-12650502-i7")
val robolectricJars = robolectricVersions.map { v ->
    configurations.create("robolectricJar_" + v.replace('-', '_')) { isTransitive = false }.also {
        dependencies.add(it.name, "org.robolectric:android-all-instrumented:$v")
    }
}
val robolectricDir = layout.buildDirectory.dir("robolectric-jars")
val copyRobolectricJars = tasks.register<Sync>("copyRobolectricJars") {
    robolectricJars.forEach { from(it) }
    into(robolectricDir)
}
tasks.withType<Test>().configureEach {
    dependsOn(copyRobolectricJars)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricDir.get().asFile.absolutePath)
}
