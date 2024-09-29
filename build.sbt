/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2024 Daniel Urban and contributors listed in NOTICE.txt
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
val scala2 = "2.13.15"
val scala3 = "3.3.4"

// CI JVM versions:
val jvmOldest = JavaSpec.temurin("11")
val jvmLts = JavaSpec.temurin("21")
val jvmLatest = JavaSpec.temurin("23")
val jvmTemurins = List(jvmOldest, jvmLts, jvmLatest)
val jvmGraalOldest = JavaSpec(JavaSpec.Distribution.GraalVM("22.3.3"), "11")
val jvmGraalLts = JavaSpec.graalvm("21")
val jvmGraalLatest = JavaSpec.graalvm("23")
val jvmGraals = List(jvmGraalOldest, jvmGraalLts, jvmGraalLatest)
val jvmOpenj9Oldest = JavaSpec.semeru("11")
val jvmOpenj9Lts = JavaSpec.semeru("21")
val jvmOpenj9Latest = JavaSpec.semeru("23")
val jvmOpenj9s = List(jvmOpenj9Oldest, jvmOpenj9Lts, jvmOpenj9Latest)

// CI OS versions:
val linux = "ubuntu-latest"
val windows = "windows-latest"
val macos = "macos-14"

val TestInternal = "test-internal"
val ciCommand = "ci"
val ciFullCommand = "ciFull"

def openJ9Options: String = {
  val opts = List("-Xgcpolicy:balanced")
  opts.map(opt => s"-J${opt}").mkString(" ")
}

val isOpenJ9Cond: String = {
  jvmOpenj9s.map { j9 =>
    val j9s = j9.render
    s"(matrix.java == '${j9s}')"
  }.mkString(" || ")
}

val isNotOpenJ9Cond: String =
  s"!(${isOpenJ9Cond})"

def commitContains(magic: String): String =
  s"contains(github.event.head_commit.message, '${magic}')"

/** If the commit msg contains "full CI", we run more things */
val fullCiCond: String =
  commitContains("full CI")

/** The opposite of "full CI" (this is the default) */
val quickCiCond: String =
  s"!(${fullCiCond})"

/** Where to run JCStress and Lincheck tests (need more CPUs) */
val stressCond: String =
  s"(matrix.os == '${macos}') || ((matrix.os == '${linux}') && (matrix.java == '${jvmLatest.render}'))"

ThisBuild / crossScalaVersions := Seq(scala2, scala3)
ThisBuild / scalaVersion := crossScalaVersions.value.head
ThisBuild / scalaOrganization := "org.scala-lang"
ThisBuild / evictionErrorLevel := Level.Warn
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

ThisBuild / tlBaseVersion := "0.4"
ThisBuild / tlUntaggedAreSnapshots := false // => we get versions like 0.4-39d987a
ThisBuild / tlJdkRelease := Some(11)
ThisBuild / sonatypeCredentialHost := xerial.sbt.Sonatype.sonatypeLegacy

// When checking version policy, ignore dependencies to internal modules whose version is like `1.2.3+4...`:
ThisBuild / versionPolicyIgnoredInternalDependencyVersions := Some("^\\d+\\.\\d+\\.\\d+\\-.*-SNAPSHOT".r)
// Otherwise require bincompat:
ThisBuild / versionPolicyIntention := Compatibility.BinaryCompatible

ThisBuild / githubWorkflowUseSbtThinClient := false
ThisBuild / githubWorkflowBuildTimeoutMinutes := Some(180)
ThisBuild / githubWorkflowPublishTargetBranches := Seq()
ThisBuild / githubWorkflowBuild := List(
  // Tests on non-OpenJ9:
  WorkflowStep.Sbt(
    List(ciCommand),
    cond = Some(s"($isNotOpenJ9Cond) && ($quickCiCond)"),
  ),
  // Full tests on non-OpenJ9:
  WorkflowStep.Sbt(
    List(ciFullCommand),
    cond = Some(s"($isNotOpenJ9Cond) && ($fullCiCond)"),
  ),
  // Tests on OpenJ9 only:
  WorkflowStep.Sbt(
    List(openJ9Options, ciCommand),
    cond = Some(s"($isOpenJ9Cond) && ($quickCiCond)"),
  ),
  // Full tests on OpenJ9 only:
  WorkflowStep.Sbt(
    List(openJ9Options, ciFullCommand),
    cond = Some(s"($isOpenJ9Cond) && ($fullCiCond)"),
  ),
  // Static analysis (doesn't work on Scala 3):
  WorkflowStep.Sbt(List("checkScalafix"), cond = Some(s"matrix.scala != '${CrossVersion.binaryScalaVersion(scala3)}'")),
) ++ List(
  // Lincheck tests (they only run if commit msg contains 'full CI' or 'stressLinchk'):
  WorkflowStep.Sbt(
    List("runLincheckTests"),
    cond = Some(s"(${stressCond}) && ((${fullCiCond}) || (${commitContains("stressLinchk")}))")
  )
) ++ stressTestNames.map { projName =>
  // JCStress tests (they only run if commit msg contains 'full CI' or the project name):
  WorkflowStep.Sbt(
    List(mkStressTestCmd(projName)),
    cond = Some(s"(${stressCond}) && ((${fullCiCond}) || (${commitContains(projName)}))")
  ),
} ++ List(
  WorkflowStep.Use(
    UseRef.Public("actions", "upload-artifact", "v4"),
    name = Some("Upload JCStress results"),
    cond = {
      val commitMsgCond = s"${fullCiCond} || (${stressTestNames.map(commitContains).mkString("", " || ", "")})"
      Some(s"(success() || failure()) && (${stressCond}) && (${commitMsgCond})")
    },
    params = Map(
      "name" -> "jcstress-results-${{ matrix.os }}-${{ matrix.scala }}-${{ matrix.java }}",
      "path" -> "results/",
    ),
  ),
)
ThisBuild / githubWorkflowJavaVersions := Seq(jvmTemurins, jvmGraals, jvmOpenj9s).flatten
ThisBuild / githubWorkflowOSes := Seq(linux, windows, macos)
ThisBuild / githubWorkflowSbtCommand := "sbt -v"
ThisBuild / githubWorkflowBuildMatrixExclusions ++= Seq(
  jvmGraals.map { gr => MatrixExclude(Map("os" -> windows, "java" -> gr.render)) }, // win+graal seems unstable
  jvmOpenj9s.map { j9 => MatrixExclude(Map("os" -> windows, "java" -> j9.render)) }, // win+openJ9 seems unstable
  // these are excluded so that we don't have too much jobs:
  jvmGraals.map { gr => MatrixExclude(Map("os" -> macos, "java" -> gr.render)) },
  jvmOpenj9s.map { j9 => MatrixExclude(Map("os" -> macos, "java" -> j9.render)) },
  jvmTemurins.map { j => MatrixExclude(Map("os" -> macos, "java" -> j.render)) }, // but see inclusions
  jvmTemurins.map { j => MatrixExclude(Map("os" -> windows, "java" -> j.render)) }, // but see inclusions
  Seq(
    MatrixExclude(Map("os" -> linux, "java" -> jvmOpenj9Oldest.render, "scala" -> CrossVersion.binaryScalaVersion(scala3))),
    MatrixExclude(Map("os" -> linux, "java" -> jvmGraalOldest.render, "scala" -> CrossVersion.binaryScalaVersion(scala3))),
  ),
).flatten
ThisBuild / githubWorkflowBuildMatrixInclusions ++= crossScalaVersions.value.flatMap { scalaVer =>
  val binVer = CrossVersion.binaryScalaVersion(scalaVer)
  Seq(
    MatrixInclude(matching = Map("os" -> macos, "java" -> jvmLatest.render, "scala" -> binVer), additions = Map.empty),
    MatrixInclude(matching = Map("os" -> windows, "java" -> jvmLatest.render, "scala" -> binVer), additions = Map.empty),
  )
}

// ThisBuild / resolvers += Resolver.mavenLocal

val disabledPlugins = Seq(
  JCStressPlugin,
  JolPlugin,
)

lazy val choam = project.in(file("."))
  .settings(name := "choam")
  .settings(commonSettings)
  .enablePlugins(NoPublishPlugin)
  .disablePlugins(disabledPlugins: _*)
  .aggregate(
    mcas.jvm, mcas.js,
    core.jvm, core.js,
    data.jvm, data.js,
    skiplist.jvm, skiplist.js,
    async.jvm, async.js,
    stm.jvm, stm.js,
    stream.jvm, stream.js,
    profiler, // JVM
    ce.jvm, ce.js,
    internalHelpers, // JVM
    laws.jvm, laws.js,
    unidocs,
    testExt.jvm, testExt.js,
    bench, // JVM
    stressOld, // JVM
    stressMcas, // JVM
    stressMcasSlow, // JVM
    stressCore, // JVM
    stressData, // JVM
    stressDataSlow, // JVM
    stressAsync, // JVM
    stressExperiments, // JVM
    stressLinchk, // JVM
    stressLinchkAgent, // JVM
    stressRng, // JVM
    layout, // JVM
  )

lazy val core = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Full)
  .withoutSuffixFor(JVMPlatform)
  .in(file("core"))
  .settings(name := "choam-core")
  .disablePlugins(disabledPlugins: _*)
  .settings(commonSettings)
  .jvmSettings(commonSettingsJvm)
  .jsSettings(commonSettingsJs)
  .dependsOn(mcas % "compile->compile;test->test")
  .settings(libraryDependencies ++= Seq(
    dependencies.catsCore.value,
    dependencies.catsMtl.value,
    dependencies.catsEffectKernel.value,
    dependencies.catsEffectStd.value,
    // "eu.timepit" %%% "refined" % "0.11.1",
  ))

lazy val mcas = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Full)
  .withoutSuffixFor(JVMPlatform)
  .in(file("mcas"))
  .settings(name := "choam-mcas")
  .disablePlugins(disabledPlugins: _*)
  .settings(commonSettings)
  .jvmSettings(commonSettingsJvm)
  .jsSettings(commonSettingsJs)
  .dependsOn(skiplist % "compile->compile;test->test")
  .settings(libraryDependencies += dependencies.catsKernel.value) // TODO: we only need this due to `Order`

lazy val skiplist = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Full)
  .withoutSuffixFor(JVMPlatform)
  .in(file("skiplist"))
  .settings(name := "choam-skiplist")
  .disablePlugins(disabledPlugins: _*)
  .settings(commonSettings)
  .jvmSettings(commonSettingsJvm)
  .jsSettings(commonSettingsJs)
  .settings(libraryDependencies ++= Seq(
    dependencies.catsKernel.value,
    dependencies.catsScalacheck.value % TestInternal,
  ))

lazy val data = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Full)
  .withoutSuffixFor(JVMPlatform)
  .in(file("data"))
  .settings(name := "choam-data")
  .disablePlugins(disabledPlugins: _*)
  .settings(commonSettings)
  .jvmSettings(commonSettingsJvm)
  .jsSettings(commonSettingsJs)
  .dependsOn(core % "compile->compile;test->test")
  .settings(libraryDependencies += dependencies.catsCollections.value)

lazy val async = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Full)
  .withoutSuffixFor(JVMPlatform)
  .in(file("async"))
  .settings(name := "choam-async")
  .disablePlugins(disabledPlugins: _*)
  .settings(commonSettings)
  .jvmSettings(commonSettingsJvm)
  .jsSettings(commonSettingsJs)
  .dependsOn(data % "compile->compile;test->test")

lazy val stm = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Full)
  .withoutSuffixFor(JVMPlatform)
  .in(file("stm"))
  .settings(name := "choam-stm")
  .disablePlugins(disabledPlugins: _*)
  .settings(commonSettings)
  .jvmSettings(commonSettingsJvm)
  .jsSettings(commonSettingsJs)
  .dependsOn(async % "compile->compile;test->test")
  .settings(
    tlVersionIntroduced := Map("2.13" -> "0.4.1", "3" -> "0.4.1"),
  )

lazy val stream = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Full)
  .withoutSuffixFor(JVMPlatform)
  .in(file("stream"))
  .settings(name := "choam-stream")
  .disablePlugins(disabledPlugins: _*)
  .settings(commonSettings)
  .jvmSettings(commonSettingsJvm)
  .jsSettings(commonSettingsJs)
  .dependsOn(async % "compile->compile;test->test")
  .settings(libraryDependencies += dependencies.fs2.value)

lazy val profiler = project.in(file("profiler"))
  .settings(name := "choam-profiler")
  .disablePlugins(disabledPlugins: _*)
  .settings(commonSettings)
  .settings(commonSettingsJvm)
  .dependsOn(core.jvm % "compile->compile;test->test")
  .settings(
    libraryDependencies ++= Seq(
      dependencies.jmh.value,
      dependencies.decline.value,
    ),
    Test / fork := true,
    Test / javaOptions ++= List(
      "-Ddev.tauri.choam.stats=true",
    ),
  )

lazy val ce = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Full)
  .withoutSuffixFor(JVMPlatform)
  .in(file("ce"))
  .settings(name := "choam-ce")
  .enablePlugins(NoPublishPlugin) // TODO: maybe publish it?
  .disablePlugins(disabledPlugins: _*)
  .settings(commonSettings)
  .jvmSettings(commonSettingsJvm)
  .jsSettings(commonSettingsJs)
  .dependsOn(async % "compile->compile;test->test")
  .settings(libraryDependencies += dependencies.catsEffectAll.value)

/** Internal use only; no published project may depend on this */
lazy val internalHelpers = project.in(file("internal-helpers"))
  .settings(name := "choam-internal-helpers")
  .enablePlugins(NoPublishPlugin)
  .disablePlugins(disabledPlugins: _*)
  .settings(commonSettings)
  .settings(commonSettingsJvm)
  .dependsOn(ce.jvm % "compile->compile;test->test")

lazy val laws = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Full)
  .withoutSuffixFor(JVMPlatform)
  .in(file("laws"))
  .settings(name := "choam-laws")
  .disablePlugins(disabledPlugins: _*)
  .settings(commonSettings)
  .jvmSettings(commonSettingsJvm)
  .jsSettings(commonSettingsJs)
  .dependsOn(async % "compile->compile;test->test")
  .settings(libraryDependencies ++= Seq(
    dependencies.catsLaws.value,
    dependencies.catsEffectLaws.value,
    dependencies.catsEffectTestkit.value % TestInternal,
  ))

lazy val unidocs = project
  .in(file("unidocs"))
  .disablePlugins(disabledPlugins: _*)
  .settings(commonSettings)
  .settings(publishSettings)
  .enablePlugins(TypelevelUnidocPlugin)
  .settings(
    name := "choam-docs",
    ScalaUnidoc / unidoc / unidocProjectFilter := inProjects(
      core.jvm,
      mcas.jvm,
      skiplist.jvm,
      data.jvm,
      async.jvm,
      stm.jvm,
      stream.jvm,
      laws.jvm,
      profiler,
    ),
    bspEnabled := false,
  )

lazy val testExt = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Full)
  .withoutSuffixFor(JVMPlatform)
  .in(file("test-ext"))
  .settings(name := "choam-test-ext")
  .enablePlugins(NoPublishPlugin)
  .disablePlugins(disabledPlugins: _*)
  .settings(commonSettings)
  .jvmSettings(commonSettingsJvm)
  .jsSettings(commonSettingsJs)
  .dependsOn(stream % "compile->compile;test->test")
  .dependsOn(stm % "compile->compile;test->test")
  .jvmSettings(
    Test / fork := true,
    Test / javaOptions ++= List(
      "-Ddev.tauri.choam.stats=true",
    ),
  )

lazy val bench = project.in(file("bench"))
  .settings(name := "choam-bench")
  .enablePlugins(NoPublishPlugin)
  .disablePlugins(disabledPlugins: _*)
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      dependencies.scalaStm.value,
      dependencies.catsStm.value,
      dependencies.zioStm.value,
      dependencies.jcTools.value,
    ),
    Test / fork := true,
    Test / javaOptions ++= List(
      "-Ddev.tauri.choam.stats=true",
    ),
  )
  .enablePlugins(JmhPlugin)
  .dependsOn(stream.jvm % "compile->compile;compile->test")
  .dependsOn(stm.jvm % "compile->compile;compile->test")
  .dependsOn(profiler % "compile->compile")
  .dependsOn(internalHelpers)
  .settings(Jmh / version := dependencies.jmhVersion)

// Stress tests (mostly with JCStress):

val disabledPluginsForStress =
  disabledPlugins.filter(_ ne JCStressPlugin)

lazy val stressMcas = project.in(file("stress") / "stress-mcas")
  .settings(name := "choam-stress-mcas")
  .settings(commonSettings)
  .settings(commonSettingsJvm)
  .settings(stressSettings)
  .disablePlugins(disabledPluginsForStress: _*)
  .enablePlugins(JCStressPlugin)
  .enablePlugins(NoPublishPlugin)
  .dependsOn(mcas.jvm % "compile->compile;test->test")

lazy val stressMcasSlow = project.in(file("stress") / "stress-mcas-slow")
  .settings(name := "choam-stress-mcas-slow")
  .settings(commonSettings)
  .settings(commonSettingsJvm)
  .settings(stressSettings)
  .disablePlugins(disabledPluginsForStress: _*)
  .enablePlugins(JCStressPlugin)
  .enablePlugins(NoPublishPlugin)
  .dependsOn(stressMcas % "compile->compile;test->test")

lazy val stressCore = project.in(file("stress") / "stress-core")
  .settings(name := "choam-stress-core")
  .settings(commonSettings)
  .settings(commonSettingsJvm)
  .settings(stressSettings)
  .disablePlugins(disabledPluginsForStress: _*)
  .enablePlugins(JCStressPlugin)
  .enablePlugins(NoPublishPlugin)
  .dependsOn(core.jvm % "compile->compile;test->test")
  .dependsOn(stressMcas % "compile->compile;test->test")

lazy val stressData = project.in(file("stress") / "stress-data")
  .settings(name := "choam-stress-data")
  .settings(commonSettings)
  .settings(commonSettingsJvm)
  .settings(stressSettings)
  .disablePlugins(disabledPluginsForStress: _*)
  .enablePlugins(JCStressPlugin)
  .enablePlugins(NoPublishPlugin)
  .dependsOn(data.jvm % "compile->compile;test->test")
  .dependsOn(stressMcas % "compile->compile;test->test")
  .dependsOn(internalHelpers)

lazy val stressDataSlow = project.in(file("stress") / "stress-data-slow")
  .settings(name := "choam-stress-data-slow")
  .settings(commonSettings)
  .settings(commonSettingsJvm)
  .settings(stressSettings)
  .disablePlugins(disabledPluginsForStress: _*)
  .enablePlugins(JCStressPlugin)
  .enablePlugins(NoPublishPlugin)
  .dependsOn(data.jvm % "compile->compile;test->test")
  .dependsOn(stressData % "compile->compile;test->test")

lazy val stressAsync = project.in(file("stress") / "stress-async")
  .settings(name := "choam-stress-async")
  .settings(commonSettings)
  .settings(commonSettingsJvm)
  .settings(stressSettings)
  .disablePlugins(disabledPluginsForStress: _*)
  .enablePlugins(JCStressPlugin)
  .enablePlugins(NoPublishPlugin)
  .dependsOn(async.jvm % "compile->compile;test->test")
  .dependsOn(stressMcas % "compile->compile;test->test")
  .dependsOn(internalHelpers)

lazy val stressExperiments = project.in(file("stress") / "stress-experiments")
  .settings(name := "choam-stress-experiments")
  .settings(commonSettings)
  .settings(commonSettingsJvm)
  .settings(stressSettings)
  .disablePlugins(disabledPluginsForStress: _*)
  .enablePlugins(JCStressPlugin)
  .enablePlugins(NoPublishPlugin)
  .dependsOn(async.jvm % "compile->compile;test->test")
  .dependsOn(stressMcas % "compile->compile;test->test")

lazy val stressLinchk = project.in(file("stress") / "stress-linchk")
  .settings(name := "choam-stress-linchk")
  .settings(commonSettings)
  .settings(commonSettingsJvm)
  .disablePlugins(disabledPlugins: _*)
  .enablePlugins(NoPublishPlugin)
  .dependsOn(async.jvm % "compile->compile;test->test")
  .settings(
    libraryDependencies += dependencies.lincheck.value,
    Test / fork := true, // otherwise the bytecode transformers won't work
    Test / test := {
      // we'll need the agent JAR to run the tests:
      (stressLinchkAgent / Compile / packageBin).value.##
      (Test / test).value
    },
    Test / testOnly := {
      // we'll need the agent JAR to run the tests:
      (stressLinchkAgent / Compile / packageBin).value.##
      (Test / testOnly).evaluated
    },
    Test / javaOptions ++= List(
      s"-javaagent:${(stressLinchkAgent / Compile / packageBin / artifactPath).value}",
      // "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:8000",
    ),
  )

lazy val stressLinchkAgent = project.in(file("stress") / "stress-linchk-agent")
  .settings(name := "choam-stress-linchk-agent")
  .settings(commonSettings)
  .settings(commonSettingsJvm)
  .disablePlugins(disabledPlugins: _*)
  .enablePlugins(NoPublishPlugin)
  .settings(
    libraryDependencies += dependencies.asm.value,
    packageOptions += Package.ManifestAttributes(
      "Premain-Class" -> "dev.tauri.choam.lcagent.Premain",
    ),
  )

lazy val stressRng = project.in(file("stress") / "stress-rng")
  .settings(name := "choam-stress-rng")
  .settings(commonSettings)
  .settings(commonSettingsJvm)
  .disablePlugins(disabledPlugins: _*)
  .enablePlugins(NoPublishPlugin)
  .enablePlugins(JavaAppPackaging)
  .dependsOn(core.jvm)
  .settings(
    // we have testing code in `main`:
    libraryDependencies ++= dependencies.test.value
  )

lazy val stressOld = project.in(file("stress") / "stress-old")
  .settings(name := "choam-stress-old")
  .settings(commonSettings)
  .settings(stressSettings)
  .settings(scalacOptions -= "-Ywarn-unused:patvars") // false positives
  .settings(libraryDependencies += dependencies.zioStm.value) // TODO: temporary
  .disablePlugins(disabledPluginsForStress: _*)
  .enablePlugins(NoPublishPlugin)
  .dependsOn(async.jvm % "compile->compile;test->test")
  .dependsOn(stressMcas % "compile->compile;test->test")
  .dependsOn(internalHelpers)

lazy val layout = project.in(file("layout"))
  .settings(name := "choam-layout")
  .disablePlugins(disabledPlugins.filter(_ ne JolPlugin): _*)
  .enablePlugins(NoPublishPlugin)
  .settings(commonSettings)
  .settings(
    libraryDependencies += dependencies.jol.value % TestInternal,
    Jol / version := dependencies.jolVersion,
    Test / fork := true // JOL doesn't like sbt classpath
  )
  .dependsOn(core.jvm % "compile->compile;test->test")

lazy val commonSettingsJvm = Seq[Setting[_]](
)

lazy val commonSettingsJs = Seq[Setting[_]](
  libraryDependencies ++= Seq(
    dependencies.scalaJsLocale.value.map(_ % TestInternal),
    Seq(dependencies.scalaJsSecRnd.value % TestInternal),
  ).flatten
)

lazy val commonSettings = Seq[Setting[_]](
  scalacOptions ++= Seq(
    "-language:higherKinds",
    // TODO: "-Xelide-below", "INFO",
    // TODO: experiment with -Ydelambdafy:inline for performance
    // TODO: experiment with -Yno-predef and/or -Yno-imports
  ),
  scalacOptions ++= (
    if (!ScalaArtifacts.isScala3(scalaVersion.value)) {
      // 2.13:
      List(
        "-Xverify",
        "-Wconf:any:warning-verbose",
        "-opt:l:inline",
        "-opt-inline-from:<sources>",
        "-Wperformance",
        "-Xsource:3-cross",
        "-Wnonunit-statement",
        "-Wvalue-discard",
      )
    } else {
      // 3.x:
      List(
        "-source:3.3",
        "-Xverify-signatures",
        // we disable warnings for `final object`, because Scala 2 doesn't always makes them final;
        // and for "value discard", because Scala 3 does't allow silencing them with `: Unit`
        "-Wconf:id=E147:s,id=E175:s,any:v",
        "-Wunused:all",
        // TODO: "-Ysafe-init", // https://github.com/lampepfl/dotty/issues/17997
        "-Ycheck-all-patmat",
        // TODO: "-Ycheck-reentrant",
        // TODO: "-Yexplicit-nulls",
        // TODO: "-Yrequire-targetName",
      )
    }
  ),
  scalacOptions --= (
    if (!ScalaArtifacts.isScala3(scalaVersion.value)) List("-Xsource:3") // we add 3-cross above
    else List()
  ),
  scalacOptions -= "-language:implicitConversions", // got it from sbt-typelevel, but don't want it
  scalacOptions -= "-language:_", // got it from sbt-typelevel, but don't want it
  Test / scalacOptions --= Seq(
    // we don't care about this in tests:
    "-Wperformance",
    // and these are to noisy in tests:
    "-Wnonunit-statement",
    "-Wvalue-discard",
  ),
  // Somewhat counter-intuitively, to really run
  // tests sequentially, we need to set this to true:
  Test / parallelExecution := true,
  // And then add this restriction:
  concurrentRestrictions += Tags.limit(Tags.Test, 1),
  // (Otherwise when running `test`, the different
  // subprojects' tests still run concurrently; see
  // https://github.com/sbt/sbt/issues/2516 and
  // https://github.com/sbt/sbt/issues/2425.)
  libraryDependencies ++= dependencies.test.value.map(_ % TestInternal),
  licenses := Seq("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0.txt")),
  headerLicense := Some(HeaderLicense.Custom(
    """|SPDX-License-Identifier: Apache-2.0
       |Copyright 2016-2024 Daniel Urban and contributors listed in NOTICE.txt
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
  )),
  // disable build server protocol for JS (idea from
  // https://github.com/typelevel/sbt-typelevel/pull/465)
  bspEnabled := crossProjectPlatform.?.value.forall(_ == JVMPlatform),
) ++ inConfig(Compile)(
  inTask(packageBin)(extraPackagingSettings) ++
  inTask(packageSrc)(extraPackagingSettings) ++
  inTask(packageDoc)(extraPackagingSettings)
) ++ publishSettings

lazy val publishSettings = Seq[Setting[_]](
  organization := "dev.tauri",
  organizationHomepage := Some(url("https://tauri.dev")),
  developers := List(
    consts.developer,
  ),
  Compile / publishArtifact := true,
  Test / publishArtifact := false,
  // Replicating some logic from sbt-typelevel-mima and
  // sbt-version-policy, because both of these plugins
  // unconditionally overwrite `mimaPreviousArtifacts`;
  // this is a blend of the two algorithms (see also
  // https://github.com/scalacenter/sbt-version-policy/issues/138):
  mimaPreviousArtifacts := {
    if (publishArtifact.value) {
      tlMimaPreviousVersions.value.map { ver =>
        val pid = projectID.value
        pid
          .withExplicitArtifacts(Vector.empty)
          .withExtraAttributes(pid.extraAttributes.filter { kv => !kv._1.stripPrefix("e:").startsWith("info.") })
          .withRevision(ver)
      }
    } else {
      Set.empty
    }
  },
  // sbt-version-policy sometimes sets this to a previous
  // snapshot, but we want proper releases:
  versionPolicyPreviousVersions := tlMimaPreviousVersions.value.toSeq.sorted,
)

lazy val extraPackagingSettings = Seq[Setting[_]](
  mappings ++= consts.additionalFiles map { f =>
    ((ThisBuild / baseDirectory).value / f) -> f
  },
  packageOptions += Package.ManifestAttributes(java.util.jar.Attributes.Name.SEALED -> "true")
)

lazy val stressSettings = Seq[Setting[_]](
  Jcstress / version := dependencies.jcstressVersion,
)

lazy val consts = new {
  val additionalFiles = Seq("LICENSE.txt", "NOTICE.txt")
  val developer = Developer(
    id = "durban",
    name = "Daniel Urban",
    email = "urban.dani@gmail.com",
    url = url("https://github.com/durban"),
  )
}

lazy val dependencies = new {

  val catsVersion = "2.12.0" // https://github.com/typelevel/cats
  val catsEffectVersion = "3.5.4" // https://github.com/typelevel/cats-effect
  val catsMtlVersion = "1.5.0" // https://github.com/typelevel/cats-mtl
  val catsCollectionsVersion = "0.9.9" // https://github.com/typelevel/cats-collections
  val fs2Version = "3.11.0" // https://github.com/typelevel/fs2
  val scalacheckEffectVersion = "2.0.0-M2" // https://github.com/typelevel/scalacheck-effect
  val jcstressVersion = "0.16" // https://github.com/openjdk/jcstress
  val jmhVersion = "1.37" // https://github.com/openjdk/jmh
  val jolVersion = "0.17" // https://github.com/openjdk/jol
  val scalaJsLocaleVersion = "1.5.4" // https://github.com/cquiroz/scala-java-locales

  val catsKernel = Def.setting("org.typelevel" %%% "cats-kernel" % catsVersion)
  val catsCore = Def.setting("org.typelevel" %%% "cats-core" % catsVersion)
  val catsKernelLaws = Def.setting("org.typelevel" %%% "cats-kernel-laws" % catsVersion)
  val catsLaws = Def.setting("org.typelevel" %%% "cats-laws" % catsVersion)
  val catsEffectKernel = Def.setting("org.typelevel" %%% "cats-effect-kernel" % catsEffectVersion)
  val catsEffectStd = Def.setting("org.typelevel" %%% "cats-effect-std" % catsEffectVersion)
  val catsEffectAll = Def.setting("org.typelevel" %%% "cats-effect" % catsEffectVersion)
  val catsEffectLaws = Def.setting("org.typelevel" %%% "cats-effect-laws" % catsEffectVersion)
  val catsEffectTestkit = Def.setting("org.typelevel" %%% "cats-effect-testkit" % catsEffectVersion)
  val catsMtl = Def.setting("org.typelevel" %%% "cats-mtl" % catsMtlVersion)
  val catsMtlLaws = Def.setting("org.typelevel" %%% "cats-mtl-laws" % catsMtlVersion)
  val catsCollections = Def.setting("org.typelevel" %%% "cats-collections-core" % catsCollectionsVersion)
  val fs2 = Def.setting("co.fs2" %%% "fs2-core" % fs2Version)
  val decline = Def.setting("com.monovore" %%% "decline" % "2.4.1") // https://github.com/bkirwi/decline

  // JVM:
  val jol = Def.setting("org.openjdk.jol" % "jol-core" % jolVersion)
  val jmh = Def.setting("org.openjdk.jmh" % "jmh-core" % jmhVersion)
  val jcTools = Def.setting("org.jctools" % "jctools-core" % "4.0.5") // https://github.com/JCTools/JCTools
  val lincheck = Def.setting("org.jetbrains.kotlinx" % "lincheck-jvm" % "2.34") // https://github.com/JetBrains/lincheck
  val asm = Def.setting("org.ow2.asm" % "asm-commons" % "9.7") // https://asm.ow2.io/

  // JS:
  val scalaJsLocale = Def.setting[Seq[ModuleID]](Seq(
    "io.github.cquiroz" %%% "scala-java-locales" % scalaJsLocaleVersion,
    "io.github.cquiroz" %%% "locales-minimal-en-db" % scalaJsLocaleVersion,
  ))
  val scalaJsSecRnd = Def.setting(("org.scala-js" %%% "scalajs-java-securerandom" % "1.0.0").cross(CrossVersion.for3Use2_13))

  val scalaStm = Def.setting("org.scala-stm" %%% "scala-stm" % "0.11.1")
  val catsStm = Def.setting("io.github.timwspence" %%% "cats-stm" % "0.13.4")
  val zioCats = Def.setting("dev.zio" %%% "zio-interop-cats" % "23.1.0.3")
  val zioStm = Def.setting("dev.zio" %%% "zio" % "2.1.9")

  val test = Def.setting[Seq[ModuleID]] {
    Seq(
      catsEffectAll.value,
      catsKernelLaws.value,
      catsLaws.value,
      "org.typelevel" %%% "cats-effect-kernel-testkit" % catsEffectVersion,
      "org.typelevel" %%% "cats-effect-testkit" % catsEffectVersion,
      catsMtlLaws.value,
      "org.typelevel" %%% "munit-cats-effect" % "2.0.0",
      "org.typelevel" %%% "scalacheck-effect" % scalacheckEffectVersion,
      "org.typelevel" %%% "scalacheck-effect-munit" % scalacheckEffectVersion,
      "org.typelevel" %%% "discipline-munit" % "2.0.0", // https://github.com/typelevel/discipline-munit
      zioCats.value,
    )
  }

  val catsScalacheck = Def.setting("io.chrisdavenport" %%% "cats-scalacheck" % "0.3.2") // https://github.com/davenverse/cats-scalacheck
}

lazy val stressTestNames = List[String](
  "stressMcas",
  "stressCore",
  "stressData",
  // "stressAsync", // TODO: this test is not useful currently
  "stressExperiments",
)

val stressTestNamesSlow = List[String](
  "stressMcasSlow",
  "stressDataSlow",
)

def mkStressTestCmd(projName: String): String =
  s"${projName}/Jcstress/run"

val stressTestCommand =
  stressTestNames.map(mkStressTestCmd).mkString(";", ";", "")

addCommandAlias("checkScalafix", "scalafixAll --check")
addCommandAlias("staticAnalysis", ";headerCheckAll;Test/compile;checkScalafix")
addCommandAlias("stressTest", stressTestCommand)
addCommandAlias("validate", ";staticAnalysis;test;stressTest")
addCommandAlias("compatCheck", ";mimaReportBinaryIssues") // TODO: versionPolicyReportDependencyIssues
addCommandAlias(ciCommand, ";headerCheckAll;Test/compile;Test/fastLinkJS;testOnly -- --exclude-tags=SLOW;compatCheck")
addCommandAlias(ciFullCommand, ";headerCheckAll;Test/compile;Test/fastLinkJS;test;compatCheck")
addCommandAlias("runLincheckTests", "stressLinchk/test")
addCommandAlias("release", ";reload;+versionPolicyReportDependencyIssues;tlRelease")
addCommandAlias("releaseHash", ";reload;tlRelease")

// profiling: `-prof jfr`
addCommandAlias("measurePerformance", "bench/jmh:run -foe true -rf json -rff results.json .*")
addCommandAlias("measureExchanger", "bench/jmh:run -foe true -rf json -rff results_exchanger.json -prof dev.tauri.choam.profiler.RxnProfiler:debug .*ExchangerBench")
addCommandAlias("quickBenchmark", "bench/jmh:run -foe true -rf json -rff results_quick.json -p size=16 .*(InterpreterBench|ChoiceCombinatorBench)")
