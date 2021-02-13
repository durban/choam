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

scalaVersion in ThisBuild := "2.13.4" // TODO: "3.0.0-M3"
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
    if (isDotty.value) {
      List("-Ykind-projector")
    } else {
      Nil
    }
  ),
  scalacOptions in (Compile, console) ~= { _.filterNot("-Ywarn-unused-import" == _).filterNot("-Ywarn-unused:imports" == _) },
  scalacOptions in (Test, console) := (scalacOptions in (Compile, console)).value,
  parallelExecution in Test := false,
  libraryDependencies ++= Seq(
    Seq(
      dependencies.cats,
      dependencies.catsMtl,
      dependencies.catsEffect
    ),
    dependencies.test.map(_ % "test-internal")
  ).flatten,
  libraryDependencies ++= (
    if (!isDotty.value) {
      List(compilerPlugin("org.typelevel" % "kind-projector" % "0.11.1" cross CrossVersion.full))
    } else {
      Nil
    }
  ),
  testFrameworks += new TestFramework("munit.Framework"),
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

  val catsVersion = "2.3.1"
  val catsMtlVersion = "1.1.1"
  val fs2Version = "2.5.0"

  val shapeless = "com.chuusai" %% "shapeless" % "2.3.3"
  val cats = "org.typelevel" %% "cats-core" % catsVersion
  val catsFree = "org.typelevel" %% "cats-free" % catsVersion
  val catsEffect = "org.typelevel" %% "cats-effect" % "3.0-65-7c98c86"
  val catsMtl = "org.typelevel" %% "cats-mtl" % catsMtlVersion

  val fs2 = "co.fs2" %% "fs2-core" % fs2Version
  val fs2io = "co.fs2" %% "fs2-io" % fs2Version

  val test = Seq(
    "org.typelevel" %% "cats-laws" % catsVersion,
    "org.typelevel" %% "cats-mtl-laws" % catsMtlVersion,
    "org.typelevel" %% "munit-cats-effect-3" % "0.12.0",
    "org.typelevel" %% "scalacheck-effect" % "0.7.0",
    "org.typelevel" %% "scalacheck-effect-munit" % "0.7.0",
    "org.typelevel" %% "discipline-munit" % "1.0.4",
  )

  val scalaStm = "org.scala-stm" %% "scala-stm" % "0.11.0"
  val catsStm = "io.github.timwspence" %% "cats-stm" % "0.10.0-M3"
  val zioStm = "dev.zio" %% "zio" % "1.0.4-2"

  val jol = "org.openjdk.jol" % "jol-core" % "0.8"
}

addCommandAlias("staticAnalysis", ";headerCheckAll;test:compile;scalastyle;test:scalastyle")
addCommandAlias("stressTest", "stress/jcstress:run")
addCommandAlias("validate", ";staticAnalysis;test;stressTest")
addCommandAlias("ci", "validate")

addCommandAlias("measurePerformance", "bench/jmh:run -t max -foe true -rf json -rff results.json .*")
addCommandAlias("measureFS", "bench/jmh:run -t max -foe true -rf json -rff results_fs.json .*FalseSharing")
addCommandAlias("measureKCAS", "bench/jmh:run -t 2 -foe true -rf json -rff results_kcas.json .*ResourceAllocationKCAS")
addCommandAlias("measureKCASRead", "bench/jmh:run -t 2 -foe true -rf json -rff results_read_kcas.json .*ReadKCAS")
addCommandAlias("measureReact", "bench/jmh:run -t max -foe true -rf json -rff results_react.json .*ResourceAllocationReact")
addCommandAlias("measureCombinators", "bench/jmh:run -t max -foe true -rf json -rff results_combinators.json .*CombinatorBench")
addCommandAlias("profileReact", "bench/jmh:run -t max -foe true -prof stack:lines=3 -rf text -rff profile_react.txt .*ResourceAllocationReact")
