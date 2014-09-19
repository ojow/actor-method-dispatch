import sbt._
import Keys._
import com.typesafe.sbt.SbtMultiJvm
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.MultiJvm


object ActorMethodDispatchBuild extends Build {
   val akkaVersion = "2.3.6"

   lazy val main = Project("main", file("."), settings = SbtMultiJvm.multiJvmSettings ++ Seq(
     scalaVersion in Global := "2.11.2",
     organization in Global := "akka",
     version in Global := "0.2-SNAPSHOT",
     name := "actor-method-dispatch",
     libraryDependencies ++= Seq(
       "com.typesafe.akka" %% "akka-remote" % akkaVersion % "test",
       "com.typesafe.akka" %% "akka-multi-node-testkit" % akkaVersion % "test",
       "org.scalatest" %% "scalatest" % "2.2.1" % "test"
     ),
     compile in MultiJvm <<= (compile in MultiJvm) triggeredBy (compile in Test),
     parallelExecution in Test := false,

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
     },

     mappings in (Compile, packageBin) ++= mappings.in(macroSub, Compile, packageBin).value,
     mappings in (Compile, packageSrc) ++= mappings.in(macroSub, Compile, packageSrc).value,
     mappings in (Compile, packageBin) ++= mappings.in(commonSub, Compile, packageBin).value,
     mappings in (Compile, packageSrc) ++= mappings.in(commonSub, Compile, packageSrc).value
   )) dependsOn(macroSub, commonSub) aggregate(macroSub, commonSub) configs (MultiJvm)

   lazy val commonSub = Project("common", file("common")) settings(
     libraryDependencies ++= Seq(
       "com.typesafe.akka" %% "akka-actor" % akkaVersion
     )
   )

   lazy val macroSub = Project("macro", file("macro")) dependsOn(commonSub) settings(
      libraryDependencies += "org.scala-lang" % "scala-reflect" % scalaVersion.value
   )
}