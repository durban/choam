/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2023 Daniel Urban and contributors listed in NOTICE.txt
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

val sbtTypelevelVersion = "0.5.0-M10"

addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.4.4")
addSbtPlugin("pl.project13.scala" % "sbt-jcstress" % "0.2.0")
addSbtPlugin("com.github.sbt" % "sbt-github-actions" % "0.15.0")
addSbtPlugin("io.crashbox" % "sbt-gpg" % "0.2.1")
addSbtPlugin("ch.epfl.scala" % "sbt-version-policy" % "2.1.0")
addSbtPlugin("org.typelevel" % "sbt-typelevel-no-publish" % sbtTypelevelVersion)
addSbtPlugin("org.typelevel" % "sbt-typelevel-versioning" % sbtTypelevelVersion)
addSbtPlugin("org.typelevel" % "sbt-typelevel-mima" % sbtTypelevelVersion)
addSbtPlugin("org.typelevel" % "sbt-typelevel-sonatype" % sbtTypelevelVersion)
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.0")
addSbtPlugin("de.heikoseeberger" % "sbt-header" % "5.10.0")
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.11.0")
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.3.1")
addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.13.1")
addSbtPlugin("net.bzzt" % "sbt-strict-scala-versions" % "0.0.1")
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.9.16")

ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := "4.7.8"
