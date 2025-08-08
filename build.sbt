/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2025 Daniel Urban and contributors listed in NOTICE.txt
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

import com.typesafe.tools.mima.core.{
  ProblemFilters,
  MissingClassProblem,
  MissingTypesProblem,
  DirectMissingMethodProblem,
  ReversedMissingMethodProblem,
  NewMixinForwarderProblem,
  StaticVirtualMemberProblem,
  IncompatibleMethTypeProblem,
}

import scala.scalanative.build.GC
import sbt.ProjectReference
import sbtcrossproject.CrossProject

// Scala versions:
val scala2 = "2.13.16"
val scala3 = "3.3.6"

// The goals with the CI matrix are to:
// - have ≦19 jobs (so that all of them can run in parallel; the +1 is sbt-dependency-submission)
// - have a good coverage of OSes, JVMs, and architectures
// The strategy to achieve this is:
// - Linux ARM64 is the "primary" platform:
//   - ARM64, because it has a weaker memory model (thus, a higher chance of finding bugs)
//   - Linux, because it seems to have multiple stable JVM implementations (OpenJDK, OpenJ9, Graal)
//   - so we have the following jobs here (9):
//     - temurin11 2.13/3
//     - temurin21 2.13
//     - temurin24 3
//     - graal21 2.13
//     - graal24 2.13/3
//     - semeru23 2.13/3
// - Additional platforms only have 2 jobs each (scala 2.13 and 3):
//   - all of these use some variant of temurin (either the latest, or the latest LTS if needed)
//   - so we have the following jobs here (10):
//     - Linux x86
//     - macOS ARM64
//     - macOS Intel
//     - Win x86
//     - Win ARM64 (this needs temurin21, as 24 is not available yet)

// CI JVM versions:
val jvmOldest = JavaSpec.temurin("11")
val jvmLts = JavaSpec.temurin("21")
val jvmLatest = JavaSpec.temurin("24")
val jvmTemurins = List(jvmOldest, jvmLts, jvmLatest)
val jvmGraalLts = JavaSpec.graalvm("21")
val jvmGraalLatest = JavaSpec.graalvm("24")
val jvmGraals = List(jvmGraalLts, jvmGraalLatest)
val jvmOpenj9Latest = JavaSpec.semeru("23")
val jvmOpenj9s = List(jvmOpenj9Latest)

// CI OS versions:
val linux = "ubuntu-24.04-arm" // ARM64
val linux86 = "ubuntu-24.04" // x86_64
val windows = "windows-2025" // x86_64
val windowsArm = "windows-11-arm" // ARM64
val macos = "macos-15" // ARM64
val macosIntel = "macos-13" // x86_64

val TestInternal = "test-internal"

val openJ9Options: String = {
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

val isGraalCond: String = {
  jvmGraals.map { g =>
    val gs = g.render
    s"(matrix.java == '${gs}')"
  }.mkString(" || ")
}

def commitContains(magic: String): String =
  s"contains(github.event.head_commit.message, '${magic}')"

/** If the commit msg contains "full CI", we run more things */
val fullCiCond: String =
  commitContains("full CI")

/** The opposite of "full CI" (this is the default) */
val quickCiCond: String =
  s"!(${fullCiCond})"

/** Where to run JCStress tests (need more CPUs) */
val stressCond: String = {
  s"((matrix.os == '${macos}') || " +
  s"(matrix.os == '${linux}') || " +
  s"(matrix.os == '${linux86}')) && " +
  s"(matrix.java == '${jvmLatest.render}')"
}

/** Where to run Lincheck tests (like above, but need older JVM) */
val stressLinchkCond: String = {
  "(" +
  s"(matrix.os != '${windows}') && " +
  s"(matrix.os != '${windowsArm}')" +
  ") && (" +
  s"(matrix.java == '${jvmLts.render}') ||" +
  s"(matrix.java == '${jvmGraalLts.render}')" +
  ")"
}

def transformWorkflowStep(step: WorkflowStep): WorkflowStep = {
  step match {
    case step: WorkflowStep.Use =>
      val newRef = step.ref match {
        case r: UseRef.Public =>
          val newRefVersion = GhActions.refVersionMapping(r.owner -> r.repo)
          r.copy(ref = newRefVersion)
        case r =>
          throw new AssertionError(s"${r.getClass().getName()} is disabled")
      }
      val step2 = step.withRef(newRef)
      GhActions.additionalParams.get(newRef.owner -> newRef.repo) match {
        case Some(addParams) => step2.withParams(step2.params ++ addParams)
        case None => step2
      }

    case _ =>
      step
  }
}

ThisBuild / crossScalaVersions := Seq(scala2, scala3)
ThisBuild / scalaVersion := crossScalaVersions.value.head
ThisBuild / scalaOrganization := "org.scala-lang"
ThisBuild / evictionErrorLevel := Level.Warn
ThisBuild / semanticdbEnabled := true

val assertionsEnabled = settingKey[Boolean]("Whether to compile `_assert` calls")
ThisBuild / assertionsEnabled := !java.lang.Boolean.getBoolean("dev.tauri.choam.build.disableAssertions")

ThisBuild / tlBaseVersion := "0.5"
ThisBuild / tlUntaggedAreSnapshots := false // => we get versions like 0.4-39d987a
ThisBuild / tlJdkRelease := Some(11)

// When checking version policy, ignore dependencies to internal modules whose version is like `1.2.3+4...`:
ThisBuild / versionPolicyIgnoredInternalDependencyVersions := Some("^\\d+\\.\\d+\\.\\d+\\-.*-SNAPSHOT".r)
// Otherwise require bincompat:
ThisBuild / versionPolicyIntention := Compatibility.BinaryCompatible

ThisBuild / githubWorkflowUseSbtThinClient := false
ThisBuild / githubWorkflowBuildTimeoutMinutes := Some(360) // this is the GH maximum
ThisBuild / githubWorkflowPublishTargetBranches := Seq()
ThisBuild / githubWorkflowBuild := List(
  // Tests on non-OpenJ9:
  WorkflowStep.Sbt(
    List("${{ matrix.ci }}"),
    cond = Some(s"($isNotOpenJ9Cond) && ($quickCiCond)"),
  ),
  // Full tests on non-OpenJ9:
  WorkflowStep.Sbt(
    List("${{ matrix.ci }}Full"),
    cond = Some(s"($isNotOpenJ9Cond) && ($fullCiCond)"),
  ),
  // Tests on OpenJ9 only:
  WorkflowStep.Sbt(
    List(openJ9Options, "${{ matrix.ci }}"),
    cond = Some(s"($isOpenJ9Cond)"),
  ),
  // Static analysis:
  WorkflowStep.Sbt(
    List("checkScalafix"),
    cond = Some(s"(matrix.os == '${linux}') && (matrix.java == '${jvmLatest.render}') && (matrix.scala == '${scala2}')"),
  ),
) ++ List(
  // Lincheck tests (they only run if commit msg contains 'stressLinchk';
  // note: in 'full CI', we don't need to run them in a separate step,
  // because they already ran due to `ciFullCommand`):
  WorkflowStep.Sbt(
    List("runLincheckTests"),
    cond = Some(s"(${stressLinchkCond}) && (${commitContains("stressLinchk")})")
  )
) ++ stressTestNames.map { projName =>
  // JCStress tests (they only run if commit msg contains the project name):
  WorkflowStep.Sbt(
    List(mkStressTestCmd(projName)),
    cond = Some(s"(${stressCond}) && (${commitContains(projName)})")
  ),
} ++ List(
  WorkflowStep.Use(
    GhActions.uploadArtifactV4,
    name = Some("Upload JCStress results"),
    cond = {
      val commitMsgCond = s"(${stressTestNames.map(commitContains).mkString("", " || ", "")})"
      Some(s"(success() || failure()) && (${stressCond}) && (${commitMsgCond})")
    },
    params = Map(
      "name" -> "jcstress-results-${{ matrix.os }}-${{ matrix.scala }}-${{ matrix.java }}",
      "path" -> "results/",
    ),
  ),
  WorkflowStep.Run(
    List("zip -r graal_dumps.zip . -i 'graal_dumps/*'"),
    name = Some("ZIP Graal dumps"),
    cond = Some(s"(success() || failure()) && (matrix.os == '${linux}') && (matrix.java == '${jvmGraalLatest.render}')"),
  ),
  WorkflowStep.Use(
    GhActions.uploadArtifactV4,
    name = Some("Upload Graal dumps"),
    cond = Some(s"(success() || failure()) && (matrix.os == '${linux}') && (matrix.java == '${jvmGraalLatest.render}')"),
    params = Map(
      "name" -> "graal-dumps-${{ matrix.os }}-${{ matrix.scala }}-${{ matrix.java }}",
      "path" -> "graal_dumps.zip",
      "if-no-files-found" -> "error",
    ),
  ),
)
ThisBuild / githubWorkflowJavaVersions := Seq(jvmTemurins, jvmGraals, jvmOpenj9s).flatten
ThisBuild / githubWorkflowOSes := Seq(linux, linux86, windows, windowsArm, macos, macosIntel)
ThisBuild / githubWorkflowSbtCommand := "sbt -v --no-server"
ThisBuild / githubWorkflowBuildMatrixAdditions ++= Map("ci" -> _quickCiAliases.keys.toList)
ThisBuild / githubWorkflowBuildMatrixExclusions ++= Seq(
  List(windows, windowsArm).map { win => MatrixExclude(Map("os" -> win)) }, // but see inclusions
  List(macos, macosIntel).map { macos => MatrixExclude(Map("os" -> macos)) }, // but see inclusions
  Seq(
    MatrixExclude(Map("os" -> linux86)), // but see inclusions
    MatrixExclude(Map("java" -> jvmGraalLts.render, "scala" -> CrossVersion.binaryScalaVersion(scala3))),
    MatrixExclude(Map("java" -> jvmLts.render, "scala" -> CrossVersion.binaryScalaVersion(scala3))),
    MatrixExclude(Map("java" -> jvmLatest.render, "scala" -> CrossVersion.binaryScalaVersion(scala2))),
  ),
).flatten
ThisBuild / githubWorkflowBuildMatrixInclusions ++= crossScalaVersions.value.flatMap { scalaVer =>
  val binVer = CrossVersion.binaryScalaVersion(scalaVer)
  Seq(
    MatrixInclude(matching = Map("os" -> macos, "java" -> jvmLatest.render, "scala" -> binVer), additions = Map.empty),
    MatrixInclude(matching = Map("os" -> macosIntel, "java" -> jvmLatest.render, "scala" -> binVer), additions = Map.empty),
    MatrixInclude(matching = Map("os" -> windows, "java" -> jvmLatest.render, "scala" -> binVer), additions = Map.empty),
    MatrixInclude(matching = Map("os" -> windowsArm, "java" -> jvmLts.render, "scala" -> binVer), additions = Map.empty),
    MatrixInclude(matching = Map("os" -> linux86, "java" -> jvmLatest.render, "scala" -> binVer), additions = Map.empty),
  )
}
ThisBuild / githubWorkflowJobSetup ~= { steps =>
  steps.map(transformWorkflowStep)
}
ThisBuild / githubWorkflowAddedJobs ~= { jobs =>
  import org.typelevel.sbt.gha.{ Permissions, PermissionValue }
  val (newJobs, foundDepSubmission) = jobs.foldLeft((List.empty[WorkflowJob], false)) { (st, job) =>
    val (acc, foundDepSubmission) = st
    val trJob = job.withSteps(job.steps.map(transformWorkflowStep))
    if (trJob.id == "dependency-submission") {
      (trJob.withPermissions(Some(Permissions.Specify(
        actions = PermissionValue.None,
        checks = PermissionValue.None,
        contents = PermissionValue.Write,
        deployments = PermissionValue.None,
        idToken = PermissionValue.None,
        issues = PermissionValue.None,
        packages = PermissionValue.None,
        pages = PermissionValue.None,
        pullRequests = PermissionValue.None,
        repositoryProjects = PermissionValue.None,
        securityEvents = PermissionValue.None,
        statuses = PermissionValue.None,
      ))) :: acc, true)
    } else {
      (trJob :: acc, foundDepSubmission)
    }
  }
  assert(foundDepSubmission)
  newJobs
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
  .aggregate(_allProjects: _*)

lazy val _allProjects: Seq[ProjectReference] = {
  _crossProjects.flatMap { crossProj =>
    crossProj.projects.values.toSeq.map(p => p : ProjectReference)
  } ++ _jvmOnlyProjects
}

lazy val choamJVM = project
  .settings(name := "choamJVM")
  .settings(commonSettings)
  .enablePlugins(NoPublishPlugin)
  .disablePlugins(disabledPlugins: _*)
  .aggregate(_jvmProjects: _*)

lazy val _jvmProjects: Seq[ProjectReference] = {
  _crossProjects.flatMap { crossProj =>
    crossProj.projects.get(JVMPlatform).map[ProjectReference](p => p).toSeq
  } ++ _jvmOnlyProjects
}

lazy val choamJS = project
  .settings(name := "choamJS")
  .settings(commonSettings)
  .enablePlugins(NoPublishPlugin)
  .disablePlugins(disabledPlugins: _*)
  .aggregate(_jsProjects: _*)

lazy val _jsProjects: Seq[ProjectReference] = {
  _crossProjects.flatMap { crossProj => crossProj.projects.get(JSPlatform).map[ProjectReference](p => p).toSeq }
}

lazy val choamNative = project
  .settings(name := "choamNative")
  .settings(commonSettings)
  .enablePlugins(NoPublishPlugin)
  .disablePlugins(disabledPlugins: _*)
  .aggregate(_nativeProjects: _*)

lazy val _nativeProjects: Seq[ProjectReference] = {
  _crossProjects.flatMap { crossProj => crossProj.projects.get(NativePlatform).map[ProjectReference](p => p).toSeq }
}

lazy val _crossProjects: Seq[CrossProject] = Seq(
  mcas,
  core,
  data,
  internal,
  async,
  stream,
  ce,
  zi,
  laws,
  testExt,
)

lazy val _jvmOnlyProjects: Seq[ProjectReference] = Seq(
  profiler,
  testAssert,
  unidocs,
  graalNiExample,
  bench,
  stressMcas,
  stressMcasSlow,
  stressCore,
  stressData,
  stressDataSlow,
  stressAsync,
  stressExperiments,
  stressLinchk,
  stressRng,
  layout,
)

lazy val core = crossProject(JVMPlatform, JSPlatform)
  .withoutSuffixFor(JVMPlatform)
  .crossType(CrossType.Full)
  .in(file("core"))
  .settings(name := "choam-core")
  .disablePlugins(disabledPlugins: _*)
  .settings(commonSettings)
  .jvmSettings(commonSettingsJvm)
  .jsSettings(commonSettingsJs)
  .dependsOn(mcas % "compile->compile;test->test")
  .settings(
    libraryDependencies ++= Seq(
      dependencies.catsCore.value,
      dependencies.catsEffectKernel.value,
      dependencies.catsEffectStd.value,
    ),
    mimaBinaryIssueFilters ++= Seq(
    ),
  ).jvmSettings(
    mimaBinaryIssueFilters ++= Seq(
    ),
  ).jsSettings(
    mimaBinaryIssueFilters ++= Seq(
    ),
  )

lazy val mcas = crossProject(JVMPlatform, JSPlatform)
  .withoutSuffixFor(JVMPlatform)
  .crossType(CrossType.Full)
  .in(file("mcas"))
  .settings(name := "choam-mcas")
  .disablePlugins(disabledPlugins: _*)
  .settings(commonSettings)
  .jvmSettings(commonSettingsJvm)
  .jsSettings(commonSettingsJs)
  .dependsOn(internal % "compile->compile;test->test")
  .settings(
    mimaBinaryIssueFilters ++= Seq(
      // there is no backward compat for `choam-mcas`:
    ),
  )

lazy val internal = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .withoutSuffixFor(JVMPlatform)
  .crossType(CrossType.Full)
  .in(file("internal"))
  .settings(name := "choam-internal")
  .disablePlugins(disabledPlugins: _*)
  .enablePlugins(BuildInfoPlugin)
  .settings(commonSettings)
  .jvmSettings(commonSettingsJvm)
  .jsSettings(commonSettingsJs)
  .nativeSettings(commonSettingsNative)
  .settings(buildInfoSettings(pkg = "dev.tauri.choam.internal"))
  .settings(
    libraryDependencies ++= Seq(
      dependencies.catsKernel.value,
    ),
  )

lazy val data = crossProject(JVMPlatform, JSPlatform)
  .withoutSuffixFor(JVMPlatform)
  .crossType(CrossType.Full)
  .in(file("data"))
  .settings(name := "choam-data")
  .disablePlugins(disabledPlugins: _*)
  .settings(commonSettings)
  .jvmSettings(commonSettingsJvm)
  .jsSettings(commonSettingsJs)
  .dependsOn(core % "compile->compile;test->test")
  .settings(
    libraryDependencies += dependencies.catsCollections.value,
    mimaBinaryIssueFilters ++= Seq(
    ),
  )

lazy val async = crossProject(JVMPlatform, JSPlatform)
  .withoutSuffixFor(JVMPlatform)
  .crossType(CrossType.Full)
  .in(file("async"))
  .settings(name := "choam-async")
  .disablePlugins(disabledPlugins: _*)
  .settings(commonSettings)
  .jvmSettings(commonSettingsJvm)
  .jsSettings(commonSettingsJs)
  .dependsOn(data % "compile->compile;test->test")

lazy val stream = crossProject(JVMPlatform, JSPlatform)
  .withoutSuffixFor(JVMPlatform)
  .crossType(CrossType.Full)
  .in(file("stream"))
  .settings(name := "choam-stream")
  .disablePlugins(disabledPlugins: _*)
  .settings(commonSettings)
  .jvmSettings(commonSettingsJvm)
  .jsSettings(commonSettingsJs)
  .dependsOn(async % "compile->compile;test->test")
  .settings(libraryDependencies ++= Seq(
    dependencies.catsEffectAll.value,
    dependencies.fs2.value,
  ))

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
  .withoutSuffixFor(JVMPlatform)
  .crossType(CrossType.Full)
  .in(file("ce"))
  .settings(name := "choam-ce")
  .disablePlugins(disabledPlugins: _*)
  .settings(commonSettings)
  .jvmSettings(commonSettingsJvm)
  .jsSettings(commonSettingsJs)
  .dependsOn(async % "compile->compile;test->test")
  .settings(
    libraryDependencies += dependencies.catsEffectAll.value,
    tlVersionIntroduced := Map("2.13" -> "0.4.11", "3" -> "0.4.11"),
  )

lazy val zi = crossProject(JVMPlatform, JSPlatform)
  .withoutSuffixFor(JVMPlatform)
  .crossType(CrossType.Full)
  .in(file("zi"))
  .settings(name := "choam-zi")
  .disablePlugins(disabledPlugins: _*)
  .settings(commonSettings)
  .jvmSettings(commonSettingsJvm)
  .jsSettings(commonSettingsJs)
  .dependsOn(async % "compile->compile;test->test")
  .settings(
    libraryDependencies ++= dependencies.zioEverything.value,
    tlVersionIntroduced := Map("2.13" -> "0.4.11", "3" -> "0.4.11"),
  )

lazy val testAssert = project.in(file("test-assert"))
  .settings(name := "choam-test-assert")
  .enablePlugins(NoPublishPlugin, BuildInfoPlugin)
  .disablePlugins(disabledPlugins: _*)
  .settings(commonSettings)
  .settings(commonSettingsJvm)
  .settings(buildInfoSettings(pkg = "dev.tauri.choam.helpers"))
  .dependsOn(ce.jvm % "compile->compile;test->test")
  .settings(
    assertionsEnabled := false, // so that we can test that they're disabled
  )

lazy val laws = crossProject(JVMPlatform, JSPlatform)
  .withoutSuffixFor(JVMPlatform)
  .crossType(CrossType.Full)
  .in(file("laws"))
  .settings(name := "choam-laws")
  .enablePlugins(NoPublishPlugin)
  .disablePlugins(disabledPlugins: _*)
  .settings(commonSettings)
  .jvmSettings(commonSettingsJvm)
  .jsSettings(commonSettingsJs)
  .dependsOn(async % "compile->compile;test->test")
  .settings(
    libraryDependencies ++= Seq(
      dependencies.catsLaws.value,
      dependencies.catsEffectLaws.value,
      dependencies.catsEffectTestkit.value % TestInternal,
    ),
    mimaBinaryIssueFilters ++= Seq(
      // there is no backward compat for `choam-laws`:
    )
  )

lazy val unidocs = project
  .in(file("unidocs"))
  .disablePlugins(disabledPlugins: _*)
  .settings(commonSettings)
  .settings(commonSettingsJvm)
  .settings(publishSettings)
  .enablePlugins(TypelevelUnidocPlugin)
  .settings(
    name := "choam-docs",
    ScalaUnidoc / unidoc / unidocProjectFilter := inProjects(
      core.jvm,
      mcas.jvm,
      internal.jvm,
      data.jvm,
      async.jvm,
      stream.jvm,
      ce.jvm,
      zi.jvm,
      profiler,
    ),
    bspEnabled := false,
  )

lazy val testExt = crossProject(JVMPlatform, JSPlatform)
  .withoutSuffixFor(JVMPlatform)
  .crossType(CrossType.Full)
  .in(file("test-ext"))
  .settings(name := "choam-test-ext")
  .enablePlugins(NoPublishPlugin)
  .disablePlugins(disabledPlugins: _*)
  .settings(commonSettings)
  .jvmSettings(commonSettingsJvm)
  .jsSettings(commonSettingsJs)
  .dependsOn(stream % "compile->compile;test->test")
  .dependsOn(ce % "compile->compile;test->test")
  .jvmSettings(
    Test / fork := true,
    Test / javaOptions ++= List(
      "-Ddev.tauri.choam.stats=true",
    ),
  )

// Note: run `show graalNiExample/GraalVMNativeImage/packageBin` in sbt
// to build a native executable (in a container), and print its path.
lazy val graalNiExample = project.in(file("graal-ni-example"))
  .settings(name := "choam-graal-ni-example")
  .enablePlugins(NoPublishPlugin)
  .enablePlugins(GraalVMNativeImagePlugin)
  .disablePlugins(disabledPlugins: _*)
  .settings(commonSettings)
  .settings(commonSettingsJvm)
  .dependsOn(ce.jvm)
  .settings(
    Compile / run / fork := true,
    GraalVMNativeImage / containerBuildImage := Some("ghcr.io/graalvm/native-image-community:24.0.0-ol8"),
    GraalVMNativeImage / graalVMNativeImageOptions ++= Seq(
      "--verbose",
      "--no-fallback",
      // "--exact-reachability-metadata", // causes problems with CE
      "--install-exit-handlers",
      // "--enable-monitoring=jvmstat,jmxserver", // doesn't seem to work
      "--static-nolibc",
      // "--static", "--libc=musl", // needs musl, but it isn't in the container image
    ),
  )

lazy val bench = project.in(file("bench"))
  .settings(name := "choam-bench")
  .enablePlugins(NoPublishPlugin)
  .disablePlugins(disabledPlugins: _*)
  .settings(commonSettings)
  .settings(commonSettingsJvm)
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
  .dependsOn(profiler % "compile->compile")
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
  .dependsOn(core.jvm % "compile->compile;test->test")

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

lazy val layout = project.in(file("layout"))
  .settings(name := "choam-layout")
  .disablePlugins(disabledPlugins.filter(_ ne JolPlugin): _*)
  .enablePlugins(NoPublishPlugin)
  .settings(commonSettings)
  .settings(commonSettingsJvm)
  .settings(
    libraryDependencies += dependencies.jol.value % TestInternal,
    Jol / version := dependencies.jolVersion,
  )
  .dependsOn(core.jvm % "compile->compile;test->test")

lazy val commonSettingsJvm = Seq[Setting[_]](
  Test / fork := true,
  Test / javaOptions ++= Seq(
    // Note: forked JVM doesn't seem to use the .jvmopts
    // file, so these are copied from there.
    "-Xms512M",
    "-Xmx6G",
    "-Xss2M",
    "-XX:+UseG1GC",
    "-Ddev.tauri.choam.stats=true",
    // These are to diagnose a mysterious CI error which only happens on graal:
    "-Djdk.graal.Dump=",
    "-Djdk.graal.PrintBackendCFG=true",
    "-Djdk.graal.MethodFilter=AbstractHamt.*",
  ),
  libraryDependencies ++= Seq(
    dependencies.zioEverything.value.map(_ % TestInternal),
  ).flatten,
)

lazy val commonSettingsJs = Seq[Setting[_]](
  libraryDependencies ++= Seq(
    dependencies.scalaJsLocale.value.map(_ % TestInternal),
    dependencies.zioEverything.value.map(_ % TestInternal),
    Seq(dependencies.scalaJsSecRnd.value % TestInternal),
  ).flatten
)

lazy val commonSettingsNative = Seq[Setting[_]](
  nativeConfig ~= { config =>
    config
      .withGC(GC.commix)
      .withMultithreading(true)
      .withSourceLevelDebuggingConfig(_.enableAll)
  }
)

lazy val commonSettings = Seq[Setting[_]](
  scalacOptions ++= Seq(
    "-language:higherKinds",
    // TODO: experiment with -Ydelambdafy:inline for performance
    // TODO: experiment with -Yno-predef and/or -Yno-imports
  ),
  scalacOptions ++= (
    if (!ScalaArtifacts.isScala3(scalaVersion.value)) {
      // 2.13:
      List(
        "-Xverify",
        "-Wconf:any:warning-verbose",
        "-opt:inline:<sources>",
        "-Wperformance",
        "-Xsource:3-cross",
        "-Wnonunit-statement",
        "-Wvalue-discard",
      ) ++ (if (assertionsEnabled.value) Nil else List("-Xelide-below", "1501"))
    } else {
      // 3.x:
      List(
        "-no-indent",
        "-Xverify-signatures",
        // we disable warnings for `final object`, because Scala 2 doesn't always makes them final;
        // and for "value discard", because Scala 3 doesn't allow silencing them with `: Unit`;
        // and for deprecations, because there are a lot (and they're internal)
        "-Wconf:any:v,cat=deprecation:s,id=E147:s,id=E175:s", // TODO: for Scala 3.7: ,msg=Ignoring \\[this\\] qualifier:s
        "-Wunused:all",
        // TODO: "-Ysafe-init", // https://github.com/lampepfl/dotty/issues/17997
        "-Ycheck-all-patmat",
        // TODO: "-Ycheck-reentrant",
        // TODO: "-Ylog:checkReentrant",
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
    // and these are too noisy in tests:
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
       |Copyright 2016-2025 Daniel Urban and contributors listed in NOTICE.txt
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
  bspEnabled := crossProjectPlatform.?.value.forall { pl =>
    (pl == JVMPlatform) || (pl == NativePlatform)
  },
) ++ inConfig(Compile)(
  inTask(packageBin)(extraPackagingSettings) ++
  inTask(packageSrc)(extraPackagingSettings) ++
  inTask(packageDoc)(extraPackagingSettings)
) ++ publishSettings

def buildInfoSettings(pkg: String): Seq[Setting[_]] = Seq(
  buildInfoPackage := pkg,
  buildInfoKeys := Seq(
    assertionsEnabled,
  ),
  buildInfoOptions ++= Seq(
    BuildInfoOption.PackagePrivate,
    BuildInfoOption.ConstantValue,
  ),
)

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

  val catsVersion = "2.13.0" // https://github.com/typelevel/cats
  val catsEffectVersion = "3.7.0-RC1" // https://github.com/typelevel/cats-effect // TODO:0.5: don't use RC
  val catsCollectionsVersion = "0.9.10" // https://github.com/typelevel/cats-collections
  val fs2Version = "3.13.0-M6" // https://github.com/typelevel/fs2 // TODO:0.5: don't use RC/M
  val scalacheckEffectVersion = "2.1.0-RC1" // https://github.com/typelevel/scalacheck-effect
  val jcstressVersion = "0.16" // https://github.com/openjdk/jcstress
  val jmhVersion = "1.37" // https://github.com/openjdk/jmh
  val jolVersion = "0.17" // https://github.com/openjdk/jol
  val scalaJsLocaleVersion = "1.5.4" // https://github.com/cquiroz/scala-java-locales
  val scalaJsTimeVersion = "2.6.0" // https://github.com/cquiroz/scala-java-time
  val zioVersion = "2.1.20" // https://github.com/zio/zio

  val catsKernel = Def.setting("org.typelevel" %%% "cats-kernel" % catsVersion)
  val catsCore = Def.setting("org.typelevel" %%% "cats-core" % catsVersion)
  val catsKernelLaws = Def.setting("org.typelevel" %%% "cats-kernel-laws" % catsVersion)
  val catsLaws = Def.setting("org.typelevel" %%% "cats-laws" % catsVersion)
  val catsEffectKernel = Def.setting("org.typelevel" %%% "cats-effect-kernel" % catsEffectVersion)
  val catsEffectStd = Def.setting("org.typelevel" %%% "cats-effect-std" % catsEffectVersion)
  val catsEffectAll = Def.setting("org.typelevel" %%% "cats-effect" % catsEffectVersion)
  val catsEffectLaws = Def.setting("org.typelevel" %%% "cats-effect-laws" % catsEffectVersion)
  val catsEffectTestkit = Def.setting("org.typelevel" %%% "cats-effect-testkit" % catsEffectVersion)
  val catsCollections = Def.setting("org.typelevel" %%% "cats-collections-core" % catsCollectionsVersion)
  val fs2 = Def.setting("co.fs2" %%% "fs2-core" % fs2Version)
  val decline = Def.setting("com.monovore" %%% "decline" % "2.5.0") // https://github.com/bkirwi/decline

  // JVM:
  val jol = Def.setting("org.openjdk.jol" % "jol-core" % jolVersion)
  val jmh = Def.setting("org.openjdk.jmh" % "jmh-core" % jmhVersion)
  val jcTools = Def.setting("org.jctools" % "jctools-core" % "4.0.5") // https://github.com/JCTools/JCTools
  val lincheck = Def.setting("org.jetbrains.kotlinx" % "lincheck-jvm" % "2.38") // https://github.com/JetBrains/lincheck

  // JS:
  val scalaJsLocale = Def.setting[Seq[ModuleID]](Seq(
    "io.github.cquiroz" %%% "scala-java-locales" % scalaJsLocaleVersion,
    "io.github.cquiroz" %%% "locales-minimal-en-db" % scalaJsLocaleVersion,
  ))
  val scalaJsTime = Def.setting[ModuleID]("io.github.cquiroz" %%% "scala-java-time" % scalaJsTimeVersion)
  val scalaJsSecRnd = Def.setting(("org.scala-js" %%% "scalajs-java-securerandom" % "1.0.0").cross(CrossVersion.for3Use2_13)) // https://github.com/scala-js/scala-js-java-securerandom

  val scalaStm = Def.setting("org.scala-stm" %%% "scala-stm" % "0.11.1") // https://github.com/scala-stm/scala-stm
  val catsStm = Def.setting("io.github.timwspence" %%% "cats-stm" % "0.13.4") // https://github.com/TimWSpence/cats-stm
  val zioCats = Def.setting("dev.zio" %%% "zio-interop-cats" % "23.1.0.5")
  val zioStm = Def.setting("dev.zio" %%% "zio" % zioVersion)
  val zioEverything = Def.setting[Seq[ModuleID]](Seq(
    zioCats.value,
    zioStm.value,
  ))

  val test = Def.setting[Seq[ModuleID]] {
    Seq(
      catsEffectAll.value,
      catsKernelLaws.value,
      catsLaws.value,
      "org.typelevel" %%% "cats-effect-kernel-testkit" % catsEffectVersion,
      "org.typelevel" %%% "cats-effect-testkit" % catsEffectVersion,
      "org.scalameta" %%% "munit" % "1.1.1", // https://github.com/scalameta/munit
      "org.typelevel" %%% "munit-cats-effect" % "2.2.0-RC1", // https://github.com/typelevel/munit-cats-effect
      "org.typelevel" %%% "scalacheck-effect" % scalacheckEffectVersion,
      "org.typelevel" %%% "scalacheck-effect-munit" % scalacheckEffectVersion,
      "org.typelevel" %%% "discipline-munit" % "2.0.0", // https://github.com/typelevel/discipline-munit
      scalaJsTime.value,
    )
  }
}

lazy val stressTestNames = List[String](
  "stressMcas",
  "stressCore",
  "stressData",
  "stressAsync",
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

Global / tlCommandAliases ++= _quickCiAliases
Global / tlCommandAliases ++= _fullCiAliases

def mkCiAlias(proj: String, full: Boolean, extraLink: Option[String] = None): List[String] = {
  List(s"project ${proj}", "headerCheckAll", "Test/compile") ++ (
    extraLink match {
      case Some(cmd) => List(cmd)
      case None => Nil
    }
  ) ++ (
    if (full) {
      List("test")
    } else {
      List("testOnly -- --exclude-tags=SLOW")
    }
  ) ++ List("compatCheck")
}

def mkCiAliases(full: Boolean): Map[String, List[String]] = {
  val lst = for {
    (baseName, proj, extra) <- List(
      ("ciJVM", "choamJVM", None),
      ("ciJS", "choamJS", Some("Test/fastLinkJS")),
      ("ciNative", "choamNative", Some("Test/nativeLink")),
    )
  } yield {
    val name = if (full) baseName + "Full" else baseName
    val alias = mkCiAlias(proj, full = full, extraLink = extra)
    (name, alias)
  }
  lst.toMap
}

lazy val _quickCiAliases = mkCiAliases(full = false)
lazy val _fullCiAliases = mkCiAliases(full = true)

addCommandAlias("checkScalafix", "scalafixAll --check")
addCommandAlias("staticAnalysis", ";headerCheckAll;Test/compile;checkScalafix")
addCommandAlias("stressTest", stressTestCommand)
addCommandAlias("validate", ";staticAnalysis;test;stressTest")
addCommandAlias("compatCheck", ";mimaReportBinaryIssues") // TODO: versionPolicyReportDependencyIssues
addCommandAlias("runLincheckTests", "stressLinchk/test")
addCommandAlias("release", ";reload;tlRelease") // TODO: +versionPolicyReportDependencyIssues
addCommandAlias("releaseHash", ";reload;tlRelease")

// profiling: `-prof jfr`
addCommandAlias("measurePerformance", "bench/Jmh/run -foe true -rf json -rff results/results.json")
addCommandAlias("measureExchanger", "bench/Jmh/run -foe true -rf json -rff results/results_exchanger.json -prof dev.tauri.choam.profiler.RxnProfiler:debug .*ExchangerBench")
addCommandAlias("quickBenchmark", "bench/Jmh/run -foe true -rf json -rff results/results_quick.json -p size=16 .*(InterpreterBench|ChoiceCombinatorBench)")
