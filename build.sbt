import com.typesafe.sbt.SbtMultiJvm
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.MultiJvm

name := "dubsub"

organization := "uk.co.panaxiom"

version := "0.4-SNAPSHOT"

scalaVersion := "2.11.1"

crossScalaVersions := Seq("2.11.1", "2.10.4")

val akkaVersion = "2.3.4"

libraryDependencies ++= Seq(
  "com.typesafe.akka"  %% "akka-actor" % akkaVersion,
  "com.typesafe.akka"  %% "akka-remote" % akkaVersion,
  "com.typesafe.akka"  %% "akka-cluster" % akkaVersion,
  "com.typesafe.akka"  %% "akka-slf4j" % akkaVersion,
  "com.typesafe.akka"  %% "akka-multi-node-testkit" % akkaVersion % "test",
  "org.scalatest"      %% "scalatest" % "2.2.0" % "test"
)

val project = Project(
  id = "dubsub",
  base = file("."),
  settings = Project.defaultSettings ++ SbtMultiJvm.multiJvmSettings ++ Seq(
    // make sure that MultiJvm test are compiled by the default test compilation
    compile in MultiJvm <<= (compile in MultiJvm) triggeredBy (compile in Test),
    // disable parallel tests
    parallelExecution in Test := false,
    // make sure that MultiJvm tests are executed by the default test target,
    // and combine the results from ordinary test and multi-jvm tests
    executeTests in Test <<= (executeTests in Test, executeTests in MultiJvm) map {
      case (testResults, multiNodeResults)  =>
        val overall =
          if (testResults.overall.id < multiNodeResults.overall.id)
            multiNodeResults.overall
          else
            testResults.overall
        Tests.Output(overall,
          testResults.events ++ multiNodeResults.events,
          testResults.summaries ++ multiNodeResults.summaries)
    }
  )
) configs (MultiJvm)