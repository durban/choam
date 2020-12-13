/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2020 Daniel Urban and contributors listed in NOTICE.txt
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

scalaVersion in ThisBuild := "2.13.4"
crossScalaVersions in ThisBuild := Seq((scalaVersion in ThisBuild).value)
scalaOrganization in ThisBuild := "org.scala-lang"

githubWorkflowPublishTargetBranches in ThisBuild := Seq()
githubWorkflowBuild in ThisBuild := Seq(
  WorkflowStep.Sbt(List("ci"))
)
githubWorkflowJavaVersions in ThisBuild := Seq(
  "adopt@1.11",
  "adopt@1.15",
  "adopt-openj9@1.15",
)

lazy val choam = project.in(file("."))
  .settings(name := "choam")
  .settings(commonSettings)
  .settings(publishArtifact := false)
  .aggregate(core, bench, stress, layout)

lazy val core = project.in(file("core"))
  .settings(name := "choam-core")
  .settings(commonSettings)

lazy val bench = project.in(file("bench"))
  .settings(name := "choam-bench")
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= (
      dependencies.scalaStm
      +: (dependencies.circe.map(_ % Test)
      :+ dependencies.fs2io % Test)
    )
  )
  .settings(macroSettings)
  .enablePlugins(JmhPlugin)
  .dependsOn(core % "compile->compile;compile->test")

lazy val stress = project.in(file("stress"))
  .settings(name := "choam-stress")
  .settings(commonSettings)
  .settings(macroSettings)
  .settings(scalacOptions -= "-Ywarn-unused:patvars") // false positives
  .enablePlugins(JCStressPlugin)
  .settings(version in Jcstress := "0.7")
  .dependsOn(core % "compile->compile;test->test")

lazy val layout = project.in(file("layout"))
  .settings(name := "choam-layout")
  .settings(commonSettings)
  .settings(
    libraryDependencies += dependencies.jol % Test,
    fork in Test := true // JOL doesn't like sbt classpath
  )
  .dependsOn(core % "compile->compile;test->test")

lazy val commonSettings = Seq[Setting[_]](
  scalacOptions ++= Seq(
    "-feature",
    "-deprecation",
    "-unchecked",
    "-encoding", "UTF-8",
    "-language:higherKinds,experimental.macros",
    "-opt:l:inline",
    "-opt-inline-from:<sources>",
    // "-Werror", TODO: reënable when possible
    // TODO: see: https://github.com/scala/bug/issues/12072
    "-Wconf:any:warning-verbose",
    "-Xlint:_",
    // "-Xfatal-warnings", TODO: reënable when possible
    "-Xelide-below", "INFO",
    "-Xmigration:2.13.3",
    "-Xsource:3.0",
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
  scalacOptions in (Compile, console) ~= { _.filterNot("-Ywarn-unused-import" == _).filterNot("-Ywarn-unused:imports" == _) },
  scalacOptions in (Test, console) := (scalacOptions in (Compile, console)).value,
  addCompilerPlugin("org.typelevel" % "kind-projector" % "0.11.1" cross CrossVersion.full),
  parallelExecution in Test := false,
  libraryDependencies ++= Seq(
    Seq(
      dependencies.cats,
      dependencies.catsFree,
      dependencies.catsEffect,
      dependencies.fs2,
      dependencies.shapeless
    ),
    dependencies.test.map(_ % "test-internal")
  ).flatten,
  organization := "dev.tauri",
  publishMavenStyle := true,
  publishArtifact := false, // TODO,
  licenses := Seq("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0.txt")),
  headerLicense := Some(HeaderLicense.Custom(
    """|SPDX-License-Identifier: Apache-2.0
       |Copyright 2016-2020 Daniel Urban and contributors listed in NOTICE.txt
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

lazy val macroSettings = Seq(
  scalacOptions += "-Ymacro-annotations",
  libraryDependencies += scalaOrganization.value % "scala-reflect" % scalaVersion.value
)

lazy val dependencies = new {

  val catsVersion = "2.3.0"
  val circeVersion = "0.14.0-M1"
  val fs2Version = "2.5.0-M1"

  val shapeless = "com.chuusai" %% "shapeless" % "2.3.3"
  val cats = "org.typelevel" %% "cats-core" % catsVersion
  val catsFree = "org.typelevel" %% "cats-free" % catsVersion
  val catsEffect = "org.typelevel" %% "cats-effect" % "2.3.0"

  val circe = Seq(
    "io.circe" %% "circe-core" % circeVersion,
    "io.circe" %% "circe-generic" % circeVersion,
    "io.circe" %% "circe-parser" % circeVersion,
    "io.circe" %% "circe-fs2" % "0.13.0"
  )

  val fs2 = "co.fs2" %% "fs2-core" % fs2Version
  val fs2io = "co.fs2" %% "fs2-io" % fs2Version

  val test = Seq(
    "org.scalatest" %% "scalatest" % "3.1.0",
    "org.typelevel" %% "discipline-scalatest" % "1.0.0-RC4",
    "org.typelevel" %% "cats-laws" % catsVersion
  )

  val scalaStm = "org.scala-stm" %% "scala-stm" % "0.11.0"

  val jol = "org.openjdk.jol" % "jol-core" % "0.8"
}

addCommandAlias("staticAnalysis", ";headerCheckAll;test:compile;scalastyle;test:scalastyle")
addCommandAlias("stressTest", "stress/jcstress:run")
addCommandAlias("validate", ";staticAnalysis;test;stressTest")
addCommandAlias("ci", "validate")

addCommandAlias("measurePerformance", "bench/jmh:run -t max -foe true -rf json -rff results.json .*")
addCommandAlias("measureFS", "bench/jmh:run -t max -foe true -rf json -rff results_fs.json .*FalseSharing")
addCommandAlias("measureBackoff", "bench/jmh:run -t max -foe true -rf json -rff results_backoff.json .*BackoffBench")
addCommandAlias("measureKCAS", "bench/jmh:run -t 2 -foe true -rf json -rff results_kcas.json .*ResourceAllocationKCAS")
addCommandAlias("measureKCASRead", "bench/jmh:run -t 2 -foe true -rf json -rff results_read_kcas.json .*ReadKCAS")
addCommandAlias("measureReact", "bench/jmh:run -t max -foe true -rf json -rff results_react.json .*ResourceAllocationReact")
addCommandAlias("measureCombinators", "bench/jmh:run -t max -foe true -rf json -rff results_combinators.json .*CombinatorBench")
addCommandAlias("profileReact", "bench/jmh:run -t max -foe true -prof stack:lines=3 -rf text -rff profile_react.txt .*ResourceAllocationReact")
