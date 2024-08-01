val scala3Version = "3.4.2"
val pekkoVersion = "1.0.2"

lazy val root = project
  .in(file("."))
  .settings(
    name := "Windowed-CRDT",
    version := "0.1.0-SNAPSHOT",

    scalaVersion := scala3Version,
    scalacOptions += "-Wunused:all",
    scalacOptions += "-Wnonunit-statement",
    scalacOptions += "-deprecation",
    scalacOptions += "-Werror",

    libraryDependencies += "org.scalameta" %% "munit" % "1.0.0" % Test,
    libraryDependencies += "org.apache.pekko" %% "pekko-actor-typed" % pekkoVersion,
    libraryDependencies += "org.apache.pekko" %% "pekko-cluster-typed" % pekkoVersion,
    libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.18" % Test,
    libraryDependencies += "org.scalacheck" %% "scalacheck" % "1.18.0" % Test,
    libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.1.3" % Runtime,
    libraryDependencies += "org.typelevel" %% "cats-core" % "2.9.0",
    libraryDependencies += "io.circe" %% "circe-yaml" % "0.14.2",
    libraryDependencies += "io.circe" %% "circe-generic" % "0.14.2"
  )

inThisBuild(
   List(
     scalaVersion := scala3Version,
     semanticdbEnabled := true, // enable SemanticDB
   )
 )