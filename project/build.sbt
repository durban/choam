/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2026 Daniel Urban and contributors listed in NOTICE.txt
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

val circeVersion = "0.14.15"
val kindProjectorVersion = "0.13.4"
val macroParadiseVersion = "2.1.1"

libraryDependencies ++= Seq(
  "io.circe" %% "circe-core" % circeVersion,
  "io.circe" %% "circe-generic" % circeVersion,
  "io.circe" %% "circe-generic-extras" % "0.14.4",
  "io.circe" %% "circe-parser" % circeVersion,
  compilerPlugin("org.typelevel" % "kind-projector" % kindProjectorVersion cross CrossVersion.full),
  compilerPlugin("org.scalamacros" % "paradise" % macroParadiseVersion cross CrossVersion.full),
)

scalacOptions ++= Seq(
  s"-P:semanticdb:sourceroot:${(ThisBuild / baseDirectory).value.absolutePath}", // metals needs this
)
