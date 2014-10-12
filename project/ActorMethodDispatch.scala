import com.typesafe.sbt.pgp.PgpKeys
import sbt._
import Keys._
import xerial.sbt.Sonatype._
import SonatypeKeys._
import com.typesafe.sbt.SbtMultiJvm
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.MultiJvm


object ActorMethodDispatch extends Build {
   val akkaVersion = "2.3.6"

   lazy val main = Project("main", file(".")).dependsOn(macroSub).settings(SbtMultiJvm.multiJvmSettings: _*).
                                              settings(sonatypeSettings: _*).settings(
     scalaVersion in Global := "2.11.2",
     scalacOptions in Global += "-feature",
     organization in Global := "net.ogalako",
     version in Global := "0.3-SNAPSHOT",
     name := "actor-method-dispatch",
     licenses := Seq("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.html")),
     profileName := "ojow",
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

     publishArtifact in Test := false,
     pomExtra := {
       <url>https://github.com/ojow/actor-method-dispatch</url>
         <scm>
           <connection>scm:git:github.com/ojow/actor-method-dispatch.git</connection>
           <developerConnection>scm:git:git@github.com:ojow/actor-method-dispatch.git</developerConnection>
           <url>github.com/ojow/actor-method-dispatch.git</url>
         </scm>
         <developers>
           <developer>
             <id>ojow</id>
             <name>Oleg Galako</name>
             <url>https://github.com/ojow</url>
           </developer>
         </developers>
     },
     pomPostProcess := { xi: scala.xml.Node =>
       import scala.xml._

       val badDeps = (xi \\ "dependency").filter(x => (x \ "groupId").text == "net.ogalako").toSet

       def filt(root: Node): Node = root match {
         case x: Elem =>
           val ch = x.child.filter(!badDeps(_)).map(filt)
           Elem(x.prefix, x.label, x.attributes, x.scope, false, ch: _*)

         case x => x
       }
       filt(xi)
     }
   ).aggregate(macroSub).configs(MultiJvm)

   lazy val macroSub = Project("macro", file("macro")) settings(
     publishTo := Some(Resolver.file("Unused transient repository", file("target/unusedrepo"))),
     publishArtifact := false,
     libraryDependencies ++= Seq(
       "com.typesafe.akka" %% "akka-actor" % akkaVersion,
       "org.scala-lang" % "scala-reflect" % scalaVersion.value
     )
   )
}