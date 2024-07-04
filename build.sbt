val scala3Version = "3.4.2"
val pekkoVersion = "1.0.2"

lazy val root = project
  .in(file("."))
  .settings(
    name := "CRDT",
    version := "0.1.0-SNAPSHOT",

    scalaVersion := scala3Version,
    scalacOptions += "-Wunused:imports",
    scalacOptions += "-Wnonunit-statement",

    libraryDependencies += "org.scalameta" %% "munit" % "1.0.0" % Test,
    libraryDependencies += "org.apache.pekko" %% "pekko-actor-typed" % pekkoVersion,
    libraryDependencies += "org.apache.pekko" %% "pekko-cluster-typed" % pekkoVersion,
    libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.18" % Test,
    libraryDependencies += "org.scalacheck" %% "scalacheck" % "1.18.0" % Test,
    libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.1.3" % Runtime,
    libraryDependencies += "org.scalaz" %% "scalaz-core" % "7.3.8",
  )

inThisBuild(
   List(
     scalaVersion := scala3Version,
     semanticdbEnabled := true, // enable SemanticDB
   )
 )
