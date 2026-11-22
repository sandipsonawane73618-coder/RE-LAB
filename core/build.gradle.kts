plugins { id("org.jetbrains.kotlin.jvm") }
kotlin { jvmToolchain(17) }
dependencies { implementation("org.jsoup:jsoup:1.18.3"); testImplementation("junit:junit:4.13.2") }
tasks.test { useJUnit() }


tasks.register<JavaExec>("runPannaHalfSangamBacktest") {
    group = "verification"
    description = "Run production Panna, both Half-Sangam directions, and Sangam walk-forward metrics from an eligible chart CSV."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.relab.core.PannaHalfSangamBacktestCli")
    workingDir = rootProject.projectDir
    doFirst {
        val input = providers.gradleProperty("inputCsv").orNull
            ?: error("Pass -PinputCsv=<eligible CSV path>")
        val output = providers.gradleProperty("outputCsv").orNull
            ?: error("Pass -PoutputCsv=<metrics CSV path>")
        args(input, output)
    }
}
