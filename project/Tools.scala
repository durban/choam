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

import scala.util.Try

import sbt.{ io => _, _ }
import sbt.Keys._
import sbt.internal.util.ManagedLogger

import cats.syntax.all._

import io.circe.{ Json, JsonObject, Decoder, Encoder }
import io.circe.parser
import io.circe.generic.JsonCodec
import io.circe.generic.extras.{ ConfiguredJsonCodec, Configuration }

object Tools extends AutoPlugin {

  final override def requires =
    plugins.JvmPlugin && pl.project13.scala.sbt.JmhPlugin

  final override def trigger =
    allRequirements

  final override def projectSettings: Seq[Setting[_]] = Seq(
    autoImport.cleanBenchResults := cleanBenchResultsImpl.value,
    autoImport.cleanBenchResultsFilePattern := "results_*.json",
    autoImport.mergeBenchResults := mergeBenchResultsImpl.evaluated,
  )

  final object autoImport {
    lazy val cleanBenchResults = taskKey[Unit]("cleanBenchResults")
    lazy val cleanBenchResultsFilePattern = settingKey[String]("cleanBenchResultsFilePattern")
    lazy val mergeBenchResults = inputKey[Unit]("mergeBenchResults")
  }

  private lazy val cleanBenchResultsImpl = Def.task[Unit] {
    CleanBenchResults.cleanBenchResults(
      baseDirectory.value,
      autoImport.cleanBenchResultsFilePattern.value,
      streams.value.log,
    )
  }

  private lazy val mergeBenchResultsImpl = Def.inputTask[Unit] {

    import complete.DefaultParsers._

    val args: Seq[String] = spaceDelimited("<arg>").parsed
    args.toList match {
      case h :: t =>
        MergeBenchResults.mergeBenchResults(h, t: _*)
      case Nil =>
        throw new IllegalArgumentException("no args")
    }
  }
}

private object MergeBenchResults {

  import Model._

  final def mergeBenchResults(output: String, inputs: String*): Unit = {
    println(output)
    println(inputs)
    // inputs match {
    //   case from :: to :: _ =>
    //     val one = parse(from)
    //     dump(one, to = to)
    // }
  }

  private[this] final def parse(file: String): ResultFile = {
    val f = Path(file).asFile
    val contents = sbt.io.IO.read(f)
    val j: Json = io.circe.parser.parse(contents).getOrElse {
      throw new IllegalArgumentException(s"not a JSON file: ${f.absolutePath}")
    }
    j.as[ResultFile].fold(
      err => throw new IllegalArgumentException(err.toString),
      ok => ok
    )
  }

  private[this] final def dump(r: ResultFile, to: String): Unit = {
    val f = Path(to).asFile
    val str = Encoder[ResultFile].apply(r).spaces4
    sbt.io.IO.write(f, str)
  }
}

private object Model {

  final type ResultFile = List[BenchmarkResult]

  private[this] final val nanString =
    "NaN"

  private[this] implicit final val doubleOrNanDecoder: Decoder[Double] = {
    Decoder.decodeDouble.or(Decoder.decodeString.emap { s =>
      if (s == nanString) Right(Double.NaN)
      else Left(s"not a Double or Nan: '${s}'")
    })
  }

  private[this] implicit final val doubleOrNanEncoder: Encoder[Double] = {
    Encoder.instance { d =>
      if (d.isNaN()) Json.fromString(nanString)
      else Encoder.encodeDouble(d)
    }
  }

  private[this] implicit val jsonCodecConfig: Configuration =
    Configuration.default.withDefaults

  @ConfiguredJsonCodec
  final case class BenchmarkResult(
    jmhVersion: String,
    benchmark: String,
    mode: String,
    threads: Int,
    forks: Int,
    jvm: String,
    jvmArgs: List[String],
    jdkVersion: String,
    vmName: String,
    vmVersion: String,
    warmupIterations: Int,
    warmupTime: String,
    warmupBatchSize: Int,
    measurementIterations: Int,
    measurementTime: String,
    measurementBatchSize: Int,
    params: JsonObject = JsonObject.empty,
    primaryMetric: Metric,
    secondaryMetrics: JsonObject = JsonObject.empty,
  )

  @JsonCodec
  final case class Metric(
    score: Double,
    scoreError: Double,
    scoreConfidence: (Double, Double),
    scorePercentiles: JsonObject,
    scoreUnit: String,
    rawData:  List[List[Double]],
  )
}

private object CleanBenchResults {

  final def cleanBenchResults(dir: File, pat: String, log: ManagedLogger): Unit = {
    println(dir.absolutePath)
    val files = sbt.io.IO.listFiles(dir, FileFilter.globFilter(pat)).toList
    val results = files.map { f =>
      log.info(s"Cleaning '${f.absolutePath}'...")
      val r = Try { cleanJsonFile(f) }
      r.failed.foreach { ex =>
        log.warn(s"Failed with ${ex.getClass().getName()}: ${ex.getMessage()}")
      }
      r
    }
    val (errs, succs) = results.partition(_.isFailure)
    if (errs.nonEmpty) {
      if (succs.nonEmpty) {
        log.warn(s"While some files were cleaned successfully, these weren't:")
        errs.foreach { err =>
          val msg = err.failed.get.getMessage()
          log.error(msg)
        }
      } else {
        log.error(s"No files were cleaned")
      }
      throw new Exception("cleanBenchResults failed")
    }
  }

  private[this] final def cleanJsonFile(f: File): Unit = {
    val contents = sbt.io.IO.read(f)
    val j: Json = io.circe.parser.parse(contents).getOrElse {
      throw new IllegalArgumentException(s"not a JSON file: ${f.absolutePath}")
    }
    cleanJson(j) match {
      case Left(err) => throw new IllegalArgumentException(f.absolutePath + ": " + err)
      case Right(j2) => sbt.io.IO.write(f, j2.spaces4)
    }
  }

  private[this] final def cleanJson(root: Json): Either[String, Json] = {
    for {
      arr <- Either.fromOption(root.asArray, "root is not an array")
      cleaned <- arr.traverse[Either[String, *], Json] { (result: Json) =>
        Either.fromOption(
          result.asObject,
          s"not an object: ${result}"
        ).flatMap(cleanBenchmarkResult)
      }
    } yield Json.arr(cleaned: _*)
  }

  private[this] final def cleanBenchmarkResult(result: JsonObject): Either[String, Json] = {
    for {
      jdkVersionJson <- Either.fromOption(result(fieldJdkVersion), s"no ${fieldJdkVersion} field")
      jdkVersion <- Either.fromOption(jdkVersionJson.asString, s"${fieldJdkVersion} is not a string: ${jdkVersionJson}")
      vmVersionJson <- Either.fromOption(result(fieldVmVersion), s"no ${fieldVmVersion} field")
      vmVersion <- Either.fromOption(vmVersionJson.asString, s"${fieldVmVersion} is not a string: ${vmVersionJson}")
      newJdkAndVmVersion <- cleanJdkAndVmVersion(jdkVersion = jdkVersion, vmVersion = vmVersion)
      newFields = result.toList.map {
        case (key, value) if (key === fieldJdkVersion) =>
          key -> Json.fromString(newJdkAndVmVersion._1)
        case (key, value) if (key === fieldVmVersion) =>
          key -> Json.fromString(newJdkAndVmVersion._2)
        case kv =>
          kv
      }
    } yield Json.obj(newFields: _*)
  }

  private[this] final def cleanJdkAndVmVersion(jdkVersion: String, vmVersion: String): Either[String, (String, String)] = {
    val jdkvComps = jdkVersion.split('.')
    if (jdkvComps.forall(c => Try(c.toInt).isSuccess)) {
      // OK, jdkVersion is integers separated by dots
      if (vmVersion.startsWith(jdkVersion)) {
        // OK, vmVersion is just jdkVersion + some extra
        Right(jdkVersion -> jdkVersion)
      } else {
        Left(s"invalid ${fieldVmVersion}: '${vmVersion}'")
      }
    } else {
      Left(s"invalid ${fieldJdkVersion}: '${jdkVersion}'")
    }
  }

  private[this] final val fieldJdkVersion = "jdkVersion"
  private[this] final val fieldVmVersion = "vmVersion"
}
