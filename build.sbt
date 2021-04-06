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

ThisBuild / scalaVersion := "2.13.5"
ThisBuild / crossScalaVersions := Seq((ThisBuild / scalaVersion).value, "3.0.0-RC1")
ThisBuild / scalaOrganization := "org.scala-lang"
ThisBuild / evictionErrorLevel := Level.Warn

ThisBuild / githubWorkflowPublishTargetBranches := Seq()
ThisBuild / githubWorkflowBuild := Seq(
  WorkflowStep.Sbt(List("ci"))
)
ThisBuild / githubWorkflowJavaVersions := Seq(
  "adopt@1.11",
  "graalvm-ce-java11@21.0",
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
  .dependsOn(mcas)

lazy val mcas = project.in(file("mcas"))
  .settings(name := "choam-mcas")
  .settings(commonSettings)

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
      dependencies.catsEffect
    ),
    dependencies.test.map(_ % "test-internal")
  ).flatten,
  libraryDependencies ++= (
    if (!ScalaArtifacts.isScala3(scalaVersion.value)) {
      List(compilerPlugin("org.typelevel" % "kind-projector" % "0.11.3" cross CrossVersion.full))
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

  val catsVersion = "2.4.2"
  val catsMtlVersion = "1.1.2"
  val catsEffectVersion = "3.0.0"

  val cats = "org.typelevel" %% "cats-core" % catsVersion
  val catsEffect = "org.typelevel" %% "cats-effect" % catsEffectVersion
  val catsMtl = "org.typelevel" %% "cats-mtl" % catsMtlVersion

  val test = Seq(
    "org.typelevel" %% "cats-laws" % catsVersion,
    "org.typelevel" %% "cats-mtl-laws" % catsMtlVersion,
    "org.typelevel" %% "munit-cats-effect-3" % "1.0.0",
    "org.typelevel" %% "scalacheck-effect" % "0.7.1",
    "org.typelevel" %% "scalacheck-effect-munit" % "0.7.1",
    "org.typelevel" %% "discipline-munit" % "1.0.7",
  )

  val scalaStm = "org.scala-stm" %% "scala-stm" % "0.11.0"
  val catsStm = "io.github.timwspence" %% "cats-stm" % "0.10.1"
  val zioStm = "dev.zio" %% "zio" % "1.0.5"

  val jol = "org.openjdk.jol" % "jol-core" % "0.15"
}

addCommandAlias("staticAnalysis", ";headerCheckAll;Test/compile;scalastyle;Test/scalastyle")
addCommandAlias("stressTest", "stress/Jcstress/run")
addCommandAlias("validate", ";staticAnalysis;test;stressTest")
addCommandAlias("ci", ";staticAnalysis;test") // TODO: re-enable stressTest

addCommandAlias("measurePerformance", "bench/jmh:run -t max -foe true -rf json -rff results.json .*")
addCommandAlias("measureFS", "bench/jmh:run -t max -foe true -rf json -rff results_fs.json .*FalseSharing")
addCommandAlias("measureKCAS", "bench/jmh:run -t 2 -foe true -rf json -rff results_kcas.json .*ResourceAllocationKCAS")
addCommandAlias("measureKCASRead", "bench/jmh:run -t 2 -foe true -rf json -rff results_read_kcas.json .*ReadKCAS")
addCommandAlias("measureReact", "bench/jmh:run -t max -foe true -rf json -rff results_react.json .*ResourceAllocationReact")
addCommandAlias("measureCombinators", "bench/jmh:run -t max -foe true -rf json -rff results_combinators.json .*CombinatorBench")
addCommandAlias("profileReact", "bench/jmh:run -t max -foe true -prof stack:lines=3 -rf text -rff profile_react.txt .*ResourceAllocationReact")
