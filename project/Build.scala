import sbt._
import Keys._

object BuildSettings {
  val buildName              = "play-actor-room"
  val buildOrganization      = "org.mandubian"
  val buildVersion           = "0.1"

  val buildScalaVersion      = "2.10.2"

  val buildSettings = Defaults.defaultSettings ++ Seq (
    scalaVersion    := buildScalaVersion,
    organization    := buildOrganization,
    version         := buildVersion
  )
}

object ApplicationBuild extends Build {
  val typesafeRepo = Seq(
    "TypesafeRepo repository snapshots" at "http://repo.typesafe.com/typesafe/releases/",
    "TypesafeRepo repository releases" at "http://repo.typesafe.com/typesafe/snapshots/"
  )

  lazy val playezwebsocket = Project(
    BuildSettings.buildName, file("."),
    settings = BuildSettings.buildSettings ++ Seq(
      resolvers ++= typesafeRepo,
      libraryDependencies ++= Seq(
        "com.typesafe.play"  %% "play" % "2.2.0" % "provided",
        "org.specs2"         %% "specs2" % "1.13" % "test",
        "junit"               % "junit" % "4.8" % "test"
      ),
      publishMavenStyle := true,
      publishTo <<= version { (version: String) =>
        val localPublishRepo = "../mandubian-mvn/"
        if(version.trim.endsWith("SNAPSHOT"))
          Some(Resolver.file("snapshots", new File(localPublishRepo + "/snapshots")))
        else Some(Resolver.file("releases", new File(localPublishRepo + "/releases")))
      },
      scalacOptions ++= Seq(
        //"-Xlog-implicits"
        //"-deprecation",
        "-feature"
      )
    )
  )
}
