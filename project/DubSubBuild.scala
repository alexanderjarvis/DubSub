import sbt._
import sbt.Keys._
import com.typesafe.sbt.SbtMultiJvm
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.{ MultiJvm }

object DubSubBuild extends Build {

  lazy val buildSettings = Defaults.defaultSettings ++ multiJvmSettings ++ Seq(
    name := "dubsub",
    organization := "uk.co.panaxiom",
    version      := "0.2-SNAPSHOT",
    scalaVersion := "2.10.1",
    // make sure that the artifacts don't have the scala version in the name
    crossPaths   := false
  )

  lazy val dubsub = Project(
    id = "dubsub",
    base = file("."),
    settings = buildSettings ++
      Seq(libraryDependencies ++= Dependencies.base)
  ) configs(MultiJvm)

  lazy val multiJvmSettings = SbtMultiJvm.multiJvmSettings ++ Seq(
    // make sure that MultiJvm test are compiled by the default test compilation
    compile in MultiJvm <<= (compile in MultiJvm) triggeredBy (compile in Test),
    // disable parallel tests
    parallelExecution in Test := false,
    // make sure that MultiJvm tests are executed by the default test target
    executeTests in Test <<=
      ((executeTests in Test), (executeTests in MultiJvm)) map {
        case ((_, testResults), (_, multiJvmResults))  =>
          val results = testResults ++ multiJvmResults
          (Tests.overall(results.values), results)
    }
  )

  object Dependencies {
    val base = Seq(
      // ---- application dependencies ----
      "com.typesafe.akka"  %% "akka-actor" % "2.1.4" ,
      "com.typesafe.akka"  %% "akka-remote" % "2.1.4" ,
      "com.typesafe.akka"  %% "akka-cluster-experimental" % "2.1.4",

      // ---- test dependencies ----
      "com.typesafe.akka" %% "akka-testkit" % "2.1.4" %
        "test" ,
      "com.typesafe.akka" %% "akka-remote-tests-experimental" % "2.1.4" %
        "test" ,
      "org.scalatest"     %% "scalatest" % "1.9.1" % "test",
      "junit"              % "junit" % "4.11" % "test"
    )
  }

}