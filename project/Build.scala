import sbt._
import Keys._

object ActorMethodDispatchBuild extends Build {
   lazy val main = Project("main", file(".")) dependsOn(macroSub, commonSub) settings(
     scalaVersion in Global := "2.11.2",
     organization in Global := "akka",
     version in Global := "0.1",
     name := "actor-method-dispatch",
     libraryDependencies ++= Seq(
       "org.scalatest" %% "scalatest" % "2.2.1" % "test"
     )

   )

   lazy val commonSub = Project("common", file("common")) settings(
     libraryDependencies ++= Seq(
       "com.typesafe.akka" %% "akka-actor" % "2.3.6"
     )
   )

   lazy val macroSub = Project("macro", file("macro")) dependsOn(commonSub) settings(
      libraryDependencies += "org.scala-lang" % "scala-reflect" % scalaVersion.value
   )
}