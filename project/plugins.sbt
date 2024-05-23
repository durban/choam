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

val sbtTypelevelVersion = "0.7.1" // https://github.com/typelevel/sbt-typelevel

addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.4.7") // https://github.com/sbt/sbt-jmh
addSbtPlugin("pl.project13.scala" % "sbt-jcstress" % "0.2.0")
addSbtPlugin("pl.project13.sbt" % "sbt-jol" %  "0.1.4")
addSbtPlugin("io.crashbox" % "sbt-gpg" % "0.2.1") // https://github.com/jodersky/sbt-gpg
addSbtPlugin("ch.epfl.scala" % "sbt-version-policy" % "3.2.1") // https://github.com/scalacenter/sbt-version-policy
addSbtPlugin("org.typelevel" % "sbt-typelevel-no-publish" % sbtTypelevelVersion)
addSbtPlugin("org.typelevel" % "sbt-typelevel-versioning" % sbtTypelevelVersion)
addSbtPlugin("org.typelevel" % "sbt-typelevel-settings" % sbtTypelevelVersion)
addSbtPlugin("org.typelevel" % "sbt-typelevel-mima" % sbtTypelevelVersion)
addSbtPlugin("org.typelevel" % "sbt-typelevel-sonatype" % sbtTypelevelVersion)
addSbtPlugin("org.typelevel" % "sbt-typelevel-github" % sbtTypelevelVersion)
addSbtPlugin("org.typelevel" % "sbt-typelevel-github-actions" % sbtTypelevelVersion)
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.2") // https://github.com/scalameta/sbt-scalafmt
addSbtPlugin("de.heikoseeberger" % "sbt-header" % "5.10.0") // https://github.com/sbt/sbt-header
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.12.1") // https://github.com/scalacenter/sbt-scalafix
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.3.2") // https://github.com/portable-scala/sbt-crossproject
addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.16.0") // https://www.scala-js.org/
addSbtPlugin("net.bzzt" % "sbt-strict-scala-versions" % "0.0.1") // https://github.com/raboof/sbt-strict-scala-versions
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.10.0") // https://github.com/sbt/sbt-native-packager

ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := "4.9.5" // https://github.com/scalameta/scalameta
