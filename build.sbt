version := "0.1.0-SNAPSHOT"
scalaVersion := "3.3.7"
organization := "dev.gertjanassies"
dependencyOverrides += "org.lz4" % "lz4-java" % "1.8.1"

lazy val commonSettings = Seq(
  libraryDependencies ++= Seq(
    "dev.zio" %% "zio" % "2.1.26",
    "dev.zio" %% "zio-streams" % "2.1.26",
    "dev.zio" %% "zio-kafka" % "3.2.0",
    "dev.zio" %% "zio-json" % "0.7.44",
    "dev.zio" %% "zio-logging" % "2.1.16",
    "dev.zio" %% "zio-logging-slf4j2" % "2.1.16",
    "org.apache.logging.log4j" % "log4j-core" % "2.22.0",
    "org.apache.logging.log4j" % "log4j-api" % "2.22.0",
    "org.apache.logging.log4j" % "log4j-slf4j2-impl" % "2.22.0",
    "dev.zio" %% "zio-test" % "2.1.26" % Test,
    "dev.zio" %% "zio-test-sbt" % "2.1.26" % Test,
    "dev.zio" %% "zio-test-magnolia" % "2.1.26" % Test,
    "dev.zio" %% "zio-kafka-testkit" % "3.2.0" % Test
  ),
  testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
)

lazy val common = (project in file("common"))
  .settings(
    name := "common",
    commonSettings
  )

lazy val coffeeBar = (project in file("coffeebar"))
  .enablePlugins(JavaAppPackaging)
  .dependsOn(common % "compile->compile;test->test")
  .settings(
    name := "coffeebar",
    commonSettings,
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-http" % "3.7.4"
    )
  )

lazy val barista = (project in file("barista"))
  .enablePlugins(JavaAppPackaging)
  .dependsOn(common % "compile->compile;test->test")
  .settings(
    name := "barista",
    commonSettings
  )

lazy val root = rootProject.autoAggregate
  .settings(
    name := "zio-kafka-coffeebar-example"
  )
