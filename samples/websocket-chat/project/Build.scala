import sbt._
import Keys._
import play.Project._

object ApplicationBuild extends Build {

  val appName         = "websocket-chat"
  val appVersion      = "1.0"

	val mandubianRepo = Seq(
	  "Mandubian repository snapshots" at "https://github.com/mandubian/mandubian-mvn/raw/master/snapshots/",
	  "Mandubian repository releases" at "https://github.com/mandubian/mandubian-mvn/raw/master/releases/"
	)

	val appDependencies = Seq(
	  "org.mandubian" %% "play-actor-room" % "0.2"
	)

  val main = play.Project(appName, appVersion, appDependencies).settings(
  resolvers ++= mandubianRepo
  )

}
