/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2021 Daniel Urban and contributors listed in NOTICE.txt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

val scala2 = "2.13.6"
val scala3 = "3.0.0"

ThisBuild / scalaVersion := scala2
ThisBuild / crossScalaVersions := Seq((ThisBuild / scalaVersion).value, scala3)
ThisBuild / scalaOrganization := "org.scala-lang"
ThisBuild / evictionErrorLevel := Level.Warn
ThisBuild / scalafixScalaBinaryVersion := scalaBinaryVersion.value
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

ThisBuild / githubWorkflowPublishTargetBranches := Seq()
ThisBuild / githubWorkflowBuild := Seq(
  WorkflowStep.Sbt(List("ci")),
  WorkflowStep.Sbt(List("checkScalafix"), cond = Some(s"matrix.scala != '${scala3}'")),
)
ThisBuild / githubWorkflowJavaVersions := Seq(
  "adopt@1.11",
  "graalvm-ce-java11@21.1",
  "adopt@1.16",
  "adopt-openj9@1.16",
)
ThisBuild / githubWorkflowOSes := Seq("ubuntu-latest", "windows-latest")

lazy val choam = project.in(file("."))
  .settings(name := "choam")
  .settings(commonSettings)
  .settings(publishArtifact := false)
  .aggregate(core, mcas, stream, bench, stress, layout)

lazy val core = project.in(file("core"))
  .settings(name := "choam-core")
  .settings(commonSettings)
  .dependsOn(mcas)

lazy val mcas = project.in(file("mcas"))
  .settings(name := "choam-mcas")
  .settings(commonSettings)

lazy val stream = project.in(file("stream"))
  .settings(name := "choam-stream")
  .settings(commonSettings)
  .dependsOn(core % "compile->compile;test->test")
  .settings(libraryDependencies += dependencies.fs2)

lazy val bench = project.in(file("bench"))
  .settings(name := "choam-bench")
  .settings(commonSettings)
  .settings(publishArtifact := false)
  .settings(libraryDependencies ++= Seq(
    dependencies.scalaStm,
    dependencies.catsStm,
    dependencies.zioStm
  ))
  .enablePlugins(JmhPlugin)
  .dependsOn(core % "compile->compile;compile->test")

lazy val stress = project.in(file("stress"))
  .settings(name := "choam-stress")
  .settings(commonSettings)
  .settings(publishArtifact := false)
  .settings(scalacOptions -= "-Ywarn-unused:patvars") // false positives
  .settings(libraryDependencies += dependencies.zioStm) // TODO: temporary
  .enablePlugins(JCStressPlugin)
  .settings(Jcstress / version := "0.8")
  .dependsOn(core % "compile->compile;test->test")

lazy val layout = project.in(file("layout"))
  .settings(name := "choam-layout")
  .settings(commonSettings)
  .settings(publishArtifact := false)
  .settings(
    libraryDependencies += dependencies.jol % Test,
    Test / fork := true // JOL doesn't like sbt classpath
  )
  .dependsOn(core % "compile->compile;test->test")

lazy val commonSettings = Seq[Setting[_]](
  scalacOptions ++= Seq(
    "-feature",
    "-deprecation",
    "-unchecked",
    "-encoding", "UTF-8",
    "-language:higherKinds,experimental.macros",
    "-target:11",
    "-release", "11",
    "-opt:l:inline",
    "-opt-inline-from:<sources>",
    "-Wconf:any:warning-verbose",
    "-Xlint:_",
    // TODO: "-Xelide-below", "INFO",
    "-Xmigration:2.13.4",
    "-Xsource:3",
    "-Xverify",
    "-Ywarn-numeric-widen",
    "-Ywarn-dead-code",
    "-Ywarn-value-discard",
    "-Ywarn-unused:implicits",
    "-Ywarn-unused:imports",
    "-Ywarn-unused:locals",
    "-Ywarn-unused:patvars",
    "-Ywarn-unused:params",
    "-Ywarn-unused:privates",
    // TODO: experiment with -Ydelambdafy:inline for performance
    // TODO: experiment with -Yno-predef and/or -Yno-imports
  ),
  scalacOptions ++= (
    if (ScalaArtifacts.isScala3(scalaVersion.value)) {
      List("-Ykind-projector")
    } else {
      Nil
    }
  ),
  Compile / console / scalacOptions ~= { _.filterNot("-Ywarn-unused-import" == _).filterNot("-Ywarn-unused:imports" == _) },
  Test / console / scalacOptions := (Compile / console / scalacOptions).value,
  javacOptions ++= Seq(
    "--release", "11", // implies "-source 11 -target 11"
    "-Xlint",
  ),
  Test / parallelExecution := false,
  libraryDependencies ++= Seq(
    Seq(
      dependencies.cats,
      dependencies.catsMtl,
      dependencies.catsEffectStd
    ),
    dependencies.test.map(_ % "test-internal")
  ).flatten,
  libraryDependencies ++= (
    if (!ScalaArtifacts.isScala3(scalaVersion.value)) {
      List(compilerPlugin("org.typelevel" % "kind-projector" % "0.13.0" cross CrossVersion.full))
    } else {
      Nil
    }
  ),
  autoAPIMappings := true,
  // apiURL := ... // TODO
  organization := "dev.tauri",
  publishMavenStyle := true,
  publishArtifact := false, // TODO,
  licenses := Seq("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0.txt")),
  headerLicense := Some(HeaderLicense.Custom(
    """|SPDX-License-Identifier: Apache-2.0
       |Copyright 2016-2021 Daniel Urban and contributors listed in NOTICE.txt
       |
       |Licensed under the Apache License, Version 2.0 (the "License");
       |you may not use this file except in compliance with the License.
       |You may obtain a copy of the License at
       |
       |    http://www.apache.org/licenses/LICENSE-2.0
       |
       |Unless required by applicable law or agreed to in writing, software
       |distributed under the License is distributed on an "AS IS" BASIS,
       |WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
       |See the License for the specific language governing permissions and
       |limitations under the License.
       |""".stripMargin
  ))
)

lazy val dependencies = new {

  val catsVersion = "2.6.1"
  val catsEffectVersion = "3.1.1"
  val catsMtlVersion = "1.2.1"
  val fs2Version = "3.0.4"
  val scalacheckEffectVersion = "1.0.2"

  val cats = "org.typelevel" %% "cats-core" % catsVersion
  val catsEffectKernel = "org.typelevel" %% "cats-effect-kernel" % catsEffectVersion
  val catsEffectStd = "org.typelevel" %% "cats-effect-std" % catsEffectVersion
  val catsEffectAll = "org.typelevel" %% "cats-effect" % catsEffectVersion
  val catsMtl = "org.typelevel" %% "cats-mtl" % catsMtlVersion
  val fs2 = "co.fs2" %% "fs2-core" % fs2Version

  val test = Seq(
    catsEffectAll,
    "org.typelevel" %% "cats-laws" % catsVersion,
    "org.typelevel" %% "cats-mtl-laws" % catsMtlVersion,
    "org.typelevel" %% "munit-cats-effect-3" % "1.0.3",
    "org.typelevel" %% "scalacheck-effect" % scalacheckEffectVersion,
    "org.typelevel" %% "scalacheck-effect-munit" % scalacheckEffectVersion,
    "org.typelevel" %% "discipline-munit" % "1.0.9",
    "dev.zio" %% "zio-interop-cats" % "3.1.1.0",
  )

  val scalaStm = "org.scala-stm" %% "scala-stm" % "0.11.1"
  val catsStm = "io.github.timwspence" %% "cats-stm" % "0.10.3"
  val zioStm = "dev.zio" %% "zio" % "1.0.8"

  val jol = "org.openjdk.jol" % "jol-core" % "0.15"
}

addCommandAlias("checkScalafix", "scalafixAll --check")
addCommandAlias("staticAnalysis", ";headerCheckAll;Test/compile;checkScalafix")
addCommandAlias("stressTest", "stress/Jcstress/run")
addCommandAlias("validate", ";staticAnalysis;test;stressTest")
addCommandAlias("ci", ";headerCheckAll;test") // TODO: re-enable stressTest

addCommandAlias("measurePerformance", "bench/jmh:run -t 2 -foe true -rf json -rff results.json .*")
addCommandAlias("profilePerformance", "bench/jmh:run -t 2 -foe true -prof stack:lines=3 -rf text -rff profile.txt .*")
