plugins { kotlin("jvm") }
kotlin { jvmToolchain(21) }
tasks.test { workingDir = rootProject.projectDir }
dependencies { testImplementation("junit:junit:4.13.2"); testImplementation("com.google.code.gson:gson:2.12.1") }
