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

 // Scala versions:
val scala2 = "2.13.7"
val scala3 = "3.1.0"

// CI JVM versions:
val jvmLatest = JavaSpec.temurin("17")
val jvmOldest = JavaSpec.temurin("11")
val jvmGraal_11 = JavaSpec.graalvm("21.3.0", "11")
val jvmGraal_17 = JavaSpec.graalvm("21.3.0", "17")
val jvmOpenj9_11 = JavaSpec(JavaSpec.Distribution.OpenJ9, "11")
val jvmOpenj9_17 = JavaSpec(JavaSpec.Distribution.OpenJ9, "17")

// CI OS versions:
val linux = "ubuntu-latest"
val windows = "windows-latest"
val macos = "macos-latest"

val TestInternal = "test-internal"

ThisBuild / scalaVersion := scala2
ThisBuild / crossScalaVersions := Seq((ThisBuild / scalaVersion).value, scala3)
ThisBuild / scalaOrganization := "org.scala-lang"
ThisBuild / evictionErrorLevel := Level.Warn
ThisBuild / scalafixScalaBinaryVersion := scalaBinaryVersion.value
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

ThisBuild / githubWorkflowUseSbtThinClient := false
ThisBuild / githubWorkflowPublishTargetBranches := Seq()
ThisBuild / githubWorkflowBuild := Seq(
  // Tests on non-OpenJ9:
  WorkflowStep.Sbt(
    List("ci"),
    cond = Some(s"(matrix.java != '${jvmOpenj9_11.render}') && (matrix.java != '${jvmOpenj9_17.render}')"),
  ),
  // Tests on OpenJ9 only:
  WorkflowStep.Run(
    githubWorkflowSbtCommand.value.split(" ").toList ++ List("-Xgcpolicy:balanced"),
    cond = Some(s"(matrix.java == '${jvmOpenj9_11.render}') || (matrix.java == '${jvmOpenj9_17.render}')"),
  ),
  // Static analysis (not working on Scala 3):
  WorkflowStep.Sbt(List("checkScalafix"), cond = Some(s"matrix.scala != '${scala3}'")),
  // JCStress tests (only usable on macos):
  WorkflowStep.Sbt(List("ciStress"), cond = Some(s"matrix.os == '${macos}'"))
)
ThisBuild / githubWorkflowJavaVersions := Seq(
  jvmOldest,
  jvmGraal_11,
  jvmGraal_17,
  jvmOpenj9_11,
  jvmOpenj9_17,
  jvmLatest,
)
ThisBuild / githubWorkflowOSes := Seq(linux, windows, macos)
ThisBuild / githubWorkflowSbtCommand := "sbt -v"
ThisBuild / githubWorkflowBuildMatrixExclusions ++= Seq(
  MatrixExclude(Map("os" -> windows, "java" -> jvmOpenj9_11.render)), // win+openJ9 seems unstable
  MatrixExclude(Map("os" -> windows, "java" -> jvmOpenj9_17.render)), // win+openJ9 seems unstable
  MatrixExclude(Map("os" -> macos)), // don't run everything on macos, but see below
)
ThisBuild / githubWorkflowBuildMatrixInclusions += MatrixInclude(
  matching = Map("os" -> macos, "java" -> jvmLatest.render, "scala" -> scala2),
  additions = Map.empty
)

lazy val choam = project.in(file("."))
  .settings(name := "choam")
  .settings(commonSettings)
  .settings(publishArtifact := false)
  .aggregate(
    mcas.jvm, mcas.js,
    mcasStress, // JVM
    core.jvm, core.js,
    data.jvm, data.js,
    async.jvm, async.js,
    stream.jvm, stream.js,
    laws.jvm, laws.js,
    bench, // JVM
    stress, // JVM
    layout, // JVM
  )

lazy val core = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Full)
  .withoutSuffixFor(JVMPlatform)
  .in(file("core"))
  .settings(name := "choam-core")
  .settings(commonSettings)
  .jvmSettings(commonSettingsJvm)
  .jsSettings(commonSettingsJs)
  .dependsOn(mcas % "compile->compile;test->test")
  .settings(libraryDependencies ++= Seq(
    dependencies.catsCore.value,
    dependencies.catsMtl.value,
    dependencies.catsEffectStd.value,
  ))
  .jvmSettings(
    libraryDependencies += dependencies.zioCats.value % Test, // https://github.com/zio/interop-cats/issues/471
  )
  .jsSettings(
    libraryDependencies += dependencies.bobcats.value
  )

lazy val mcas = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Full)
  .withoutSuffixFor(JVMPlatform)
  .in(file("mcas"))
  .settings(name := "choam-mcas")
  .settings(commonSettings)
  .jvmSettings(commonSettingsJvm)
  .jsSettings(commonSettingsJs)

lazy val mcasStress = project.in(file("mcas-stress"))
  .settings(name := "choam-mcas-stress")
  .settings(commonSettings)
  .settings(stressSettings)
  .enablePlugins(JCStressPlugin)
  .dependsOn(mcas.jvm % "compile->compile;test->test")

lazy val data = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Full)
  .withoutSuffixFor(JVMPlatform)
  .in(file("data"))
  .settings(name := "choam-data")
  .settings(commonSettings)
  .jvmSettings(commonSettingsJvm)
  .jsSettings(commonSettingsJs)
  .dependsOn(core % "compile->compile;test->test")
  .jvmSettings(libraryDependencies += dependencies.paguro.value)

lazy val async = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Full)
  .withoutSuffixFor(JVMPlatform)
  .in(file("async"))
  .settings(name := "choam-async")
  .settings(commonSettings)
  .jvmSettings(commonSettingsJvm)
  .jsSettings(commonSettingsJs)
  .dependsOn(data % "compile->compile;test->test")

lazy val stream = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Full)
  .withoutSuffixFor(JVMPlatform)
  .in(file("stream"))
  .settings(name := "choam-stream")
  .settings(commonSettings)
  .jvmSettings(commonSettingsJvm)
  .jsSettings(commonSettingsJs)
  .dependsOn(async % "compile->compile;test->test")
  .settings(libraryDependencies += dependencies.fs2.value)

lazy val laws = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Full)
  .withoutSuffixFor(JVMPlatform)
  .in(file("laws"))
  .settings(name := "choam-laws")
  .settings(commonSettings)
  .jvmSettings(commonSettingsJvm)
  .jsSettings(commonSettingsJs)
  .dependsOn(async % "compile->compile;test->test")
  .settings(libraryDependencies ++= Seq(
    dependencies.catsLaws.value,
    dependencies.catsEffectLaws.value,
    dependencies.catsEffectTestkit.value % TestInternal,
  ))

lazy val bench = project.in(file("bench"))
  .settings(name := "choam-bench")
  .settings(commonSettings)
  .settings(publishArtifact := false)
  .settings(libraryDependencies ++= Seq(
    dependencies.scalaStm.value,
    dependencies.catsStm.value,
    dependencies.zioStm.value,
  ))
  .enablePlugins(JmhPlugin)
  .settings(jmhSettings)
  .dependsOn(stream.jvm % "compile->compile;compile->test")

lazy val stress = project.in(file("stress"))
  .settings(name := "choam-stress")
  .settings(commonSettings)
  .settings(stressSettings)
  .settings(scalacOptions -= "-Ywarn-unused:patvars") // false positives
  .settings(libraryDependencies += dependencies.zioStm.value) // TODO: temporary
  .enablePlugins(JCStressPlugin)
  .dependsOn(async.jvm % "compile->compile;test->test")
  .dependsOn(mcasStress % "compile->compile;test->test")

lazy val layout = project.in(file("layout"))
  .settings(name := "choam-layout")
  .settings(commonSettings)
  .settings(publishArtifact := false)
  .settings(
    libraryDependencies += dependencies.jol.value % Test,
    Test / fork := true // JOL doesn't like sbt classpath
  )
  .dependsOn(core.jvm % "compile->compile;test->test")

lazy val commonSettingsJvm = Seq[Setting[_]](
)

lazy val commonSettingsJs = Seq[Setting[_]](
  libraryDependencies ++= dependencies.scalaJsLocale.value.map(_ % TestInternal),
)

lazy val commonSettings = Seq[Setting[_]](
  scalacOptions ++= Seq(
    "-feature",
    "-deprecation",
    "-unchecked",
    "-encoding", "UTF-8",
    "-language:higherKinds,experimental.macros",
    "-release", "11",
    "-Xmigration:2.13.6",
    // TODO: "-Xelide-below", "INFO",
    // TODO: experiment with -Ydelambdafy:inline for performance
    // TODO: experiment with -Yno-predef and/or -Yno-imports
  ),
  scalacOptions ++= (
    if (!ScalaArtifacts.isScala3(scalaVersion.value)) {
      // 2.13:
      List(
        "-target:11",
        "-Xsource:3",
        "-Xverify",
        "-Wconf:any:warning-verbose",
        "-Ywarn-unused:implicits",
        "-Ywarn-unused:imports",
        "-Ywarn-unused:locals",
        "-Ywarn-unused:patvars",
        "-Ywarn-unused:params",
        "-Ywarn-unused:privates",
        // no equivalent:
        "-opt:l:inline",
        "-opt-inline-from:<sources>",
        "-Xlint:_",
        "-Ywarn-numeric-widen",
        "-Ywarn-dead-code",
        "-Ywarn-value-discard",
      )
    } else {
      // 3.x:
      List(
        // -release implies -Xtarget
        "-source:3.0",
        "-Xverify-signatures",
        "-Wconf:any:v",
        "-Wunused:all",
        // no equivalent:
        "-Ykind-projector",
        "-Ysafe-init",
        "-Ycheck-all-patmat",
        // TODO: "-Ycheck-reentrant",
        // TODO: "-Yexplicit-nulls",
        // TODO: "-Yrequire-targetName",
      )
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
      dependencies.catsKernel.value, // TODO: mcas only needs this due to `Order`
    ),
    dependencies.test.value.map(_ % TestInternal)
  ).flatten,
  libraryDependencies ++= (
    if (!ScalaArtifacts.isScala3(scalaVersion.value)) {
      List(compilerPlugin("org.typelevel" % "kind-projector" % dependencies.kindProjectorVersion cross CrossVersion.full))
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

lazy val stressSettings = Seq[Setting[_]](
  publishArtifact := false,
  Jcstress / version := dependencies.jcstressVersion,
)

lazy val jmhSettings = Seq[Setting[_]](
  Jmh / version := dependencies.jmhVersion,
  Jmh / bspEnabled := false, // https://github.com/sbt/sbt-jmh/issues/193
)

lazy val dependencies = new {

  val catsVersion = "2.7.0"
  val catsEffectVersion = "3.3.1"
  val catsMtlVersion = "1.2.1"
  val fs2Version = "3.2.3"
  val scalacheckEffectVersion = "1.0.3"
  val kindProjectorVersion = "0.13.2"
  val jcstressVersion = "0.15"
  val jmhVersion = "1.33"
  val scalaJsLocaleVersion = "1.2.1"

  val catsKernel = Def.setting("org.typelevel" %%% "cats-kernel" % catsVersion)
  val catsCore = Def.setting("org.typelevel" %%% "cats-core" % catsVersion)
  val catsLaws = Def.setting("org.typelevel" %%% "cats-laws" % catsVersion)
  val catsEffectKernel = Def.setting("org.typelevel" %%% "cats-effect-kernel" % catsEffectVersion)
  val catsEffectStd = Def.setting("org.typelevel" %%% "cats-effect-std" % catsEffectVersion)
  val catsEffectAll = Def.setting("org.typelevel" %%% "cats-effect" % catsEffectVersion)
  val catsEffectLaws = Def.setting("org.typelevel" %%% "cats-effect-laws" % catsEffectVersion)
  val catsEffectTestkit = Def.setting("org.typelevel" %%% "cats-effect-testkit" % catsEffectVersion)
  val catsMtl = Def.setting("org.typelevel" %%% "cats-mtl" % catsMtlVersion)
  val catsMtlLaws = Def.setting("org.typelevel" %%% "cats-mtl-laws" % catsMtlVersion)
  val fs2 = Def.setting("co.fs2" %%% "fs2-core" % fs2Version)

  // JVM:
  val paguro = Def.setting("org.organicdesign" % "Paguro" % "3.7.0")

  // JS:
  val scalaJsLocale = Def.setting[Seq[ModuleID]](Seq(
    "io.github.cquiroz" %%% "scala-java-locales" % scalaJsLocaleVersion,
    "io.github.cquiroz" %%% "locales-minimal-en-db" % scalaJsLocaleVersion,
  ))
  val bobcats = Def.setting("com.armanbilge" %%% "bobcats" % "0.1-23c4e4e")

  val test = Def.setting[Seq[ModuleID]] {
    Seq(
      catsEffectAll.value,
      catsLaws.value,
      "org.typelevel" %%% "cats-effect-kernel-testkit" % catsEffectVersion,
      "org.typelevel" %%% "cats-effect-testkit" % catsEffectVersion,
      catsMtlLaws.value,
      "org.typelevel" %%% "munit-cats-effect-3" % "1.0.7",
      "org.typelevel" %%% "scalacheck-effect" % scalacheckEffectVersion,
      "org.typelevel" %%% "scalacheck-effect-munit" % scalacheckEffectVersion,
      "org.typelevel" %%% "discipline-munit" % "1.0.9",
    )
  }

  val scalaStm = Def.setting("org.scala-stm" %%% "scala-stm" % "0.11.1")
  val catsStm = Def.setting("io.github.timwspence" %%% "cats-stm" % "0.11.0")
  val zioCats = Def.setting("dev.zio" %%% "zio-interop-cats" % "3.3.0-RC1")
  val zioStm = Def.setting("dev.zio" %%% "zio" % "2.0.0-RC1")

  val jol = Def.setting("org.openjdk.jol" % "jol-core" % "0.16")
}

addCommandAlias("checkScalafix", "scalafixAll --check")
addCommandAlias("staticAnalysis", ";headerCheckAll;Test/compile;checkScalafix")
addCommandAlias("stressTest", ";mcasStress/Jcstress/run;stress/Jcstress/run")
addCommandAlias("validate", ";staticAnalysis;test;stressTest")
addCommandAlias("ci", ";headerCheckAll;test")
addCommandAlias("ciStress", "stressTest")

// profiling: `-prof jfr`
addCommandAlias("measurePerformance", "bench/jmh:run -foe true -rf json -rff results.json .*")
addCommandAlias("quickBenchmark", "bench/jmh:run -foe true -rf json -rff results_quick.json -p size=16 .*(InterpreterBench|ChoiceCombinatorBench)")
