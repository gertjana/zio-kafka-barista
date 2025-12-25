ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.3.6"
ThisBuild / organization := "dev.gertjanassies"

lazy val commonSettings = Seq(
  libraryDependencies ++= Seq(
    "dev.zio" %% "zio" % "2.1.23",
    "dev.zio" %% "zio-streams" % "2.1.23",
    "dev.zio" %% "zio-kafka" % "3.2.0",
    "dev.zio" %% "zio-json" % "0.7.44",
    "dev.zio" %% "zio-logging" % "2.1.16",
    "dev.zio" %% "zio-logging-slf4j2" % "2.1.16",
    "org.apache.logging.log4j" % "log4j-core" % "2.22.0",
    "org.apache.logging.log4j" % "log4j-api" % "2.22.0",
    "org.apache.logging.log4j" % "log4j-slf4j2-impl" % "2.22.0"
  )
)

lazy val common = (project in file("common"))
  .settings(
    name := "common",
    commonSettings
  )

lazy val coffeeBar = (project in file("coffeebar"))
  .enablePlugins(JavaAppPackaging)
  .dependsOn(common)
  .settings(
    name := "coffeebar",
    commonSettings,
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-http" % "3.7.4"
    )
  )

lazy val barista = (project in file("barista"))
  .enablePlugins(JavaAppPackaging)
  .dependsOn(common)
  .settings(
    name := "barista",
    commonSettings
  )

lazy val root = (project in file("."))
  .aggregate(coffeeBar, barista, common)
  .settings(
    name := "zio-kafka-coffeebar-example"
  )
