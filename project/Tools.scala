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

import scala.util.Try
import scala.annotation.tailrec

import sbt.{ io => _, _ }
import sbt.Keys._
import sbt.internal.util.ManagedLogger
import sbt.io.{ IO => SbtIO }

import cats.kernel.Order
import cats.data.Chain
import cats.syntax.all._

import io.circe.{ Json, JsonObject, Decoder, Encoder }
import io.circe.parser
import io.circe.generic.JsonCodec
import io.circe.generic.extras.{ ConfiguredJsonCodec, Configuration }
import org.openjdk.jmh.results

object Tools extends AutoPlugin {

  final override def requires =
    plugins.JvmPlugin && pl.project13.scala.sbt.JmhPlugin

  final override def trigger =
    allRequirements

  final override def projectSettings: Seq[Setting[_]] = Seq(
    autoImport.cleanBenchResults := cleanBenchResultsImpl.value,
    autoImport.cleanBenchResultsFilePattern := "results_*.json",
    autoImport.mergeBenchResults := mergeBenchResultsImpl.evaluated,
    autoImport.addBenchParam := addBenchParamImpl.evaluated,
    autoImport.removeBenchParam := removeBenchParamImpl.evaluated,
  )

  final object autoImport {
    lazy val cleanBenchResults = taskKey[Unit]("cleanBenchResults")
    lazy val cleanBenchResultsFilePattern = settingKey[String]("cleanBenchResultsFilePattern")
    lazy val mergeBenchResults = inputKey[Unit]("mergeBenchResults")
    lazy val addBenchParam = inputKey[Unit]("addBenchParam")
    lazy val removeBenchParam = inputKey[Unit]("removeBenchParam")
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
        MergeBenchResults.mergeBenchResults(
          baseDirectory.value,
          streams.value.log,
          h,
          t: _*
        )
      case Nil =>
        throw new IllegalArgumentException("no args")
    }
  }

  private lazy val addBenchParamImpl =
    transformFilesTask(
      (p, v) => s"Adding param '${p}=${v}' to files:",
      MergeBenchResults.addParam,
    )

  private lazy val removeBenchParamImpl =
    transformFilesTask(
      (p, v) => s"Removing param '${p}=${v}' from files:",
      MergeBenchResults.removeParam,
    )

  private def transformFilesTask(
    label: (String, String) => String,
    transformation: (Model.BenchmarkResult, String, String) => Model.BenchmarkResult,
  ): Def.Initialize[InputTask[Unit]] = Def.inputTask[Unit] {

    import complete.DefaultParsers._

    val args: Seq[String] = spaceDelimited("<arg>").parsed
    args.toList match {
      case p :: v :: patterns =>
        MergeBenchResults.transformFiles(
          baseDirectory.value,
          streams.value.log,
          inputPatterns = patterns,
          transformation = transformation(_, p, v),
          label = label(p, v),
        )
      case _ =>
        throw new IllegalArgumentException("not enough args")
    }
  }
}

private object MergeBenchResults {

  import Model._

  final def transformFiles(
    baseDir: File,
    log: ManagedLogger,
    inputPatterns: List[String],
    transformation: BenchmarkResult => BenchmarkResult,
    label: String,
  ): Unit = {
    val inputFiles: List[File] = inputPatterns.flatMap { pattern =>
      val files = SbtIO.listFiles(baseDir, FileFilter.globFilter(pattern))
      files.toList
    }.toSet.toList.sortBy((f: File) => f.absolutePath)
    log.info(label)
    inputFiles.foreach { f =>
      log.info(s" * ${f.absolutePath}")
      transformFile(f, transformation, log)
    }
  }

  private[this] final def transformFile(
    file: File,
    transformation: BenchmarkResult => BenchmarkResult,
    log: ManagedLogger,
  ): Unit = {
    val r = parse(file, log)
    val rr = r.map(transformation)
    dump(rr, file)
  }

  final def addParam(
    br: BenchmarkResult,
    param: String,
    value: String
  ): BenchmarkResult = {
    if (br.params.contains(param)) {
      throw new IllegalArgumentException(s"Benchmark '${br.name}' already contains param '${}'")
    } else {
      br.copy(params = (param -> Json.fromString(value)) +: br.params)
    }
  }

  final def removeParam(
    br: BenchmarkResult,
    param: String,
    value: String, // if null, value is "don't care"
  ): BenchmarkResult = {
    val newParams = if (br.params.contains(param)) {
      value match {
        case null =>
          br.params.filterKeys(_ =!= param)
        case value =>
          val valueJson = Json.fromString(value)
          br.params.filter { case (k, v) =>
            if (k =!= param) true
            else (v =!= valueJson)
          }
      }
    } else {
      br.params
    }
    br.copy(params = newParams)
  }

  final def mergeBenchResults(
    baseDir: File,
    log: ManagedLogger,
    output: String,
    inputs: String*
  ): Unit = {
    val outFile: File = baseDir / output
    require(!outFile.exists(), s"${outFile.absolutePath} already exists")
    val inputFiles: List[File] = inputs.map { pattern =>
      val files = SbtIO.listFiles(baseDir, FileFilter.globFilter(pattern))
      files.toList
    }.foldLeft(Set.empty[File]) { (acc, files) =>
      val fileSet = files.toSet
      val intersection = acc intersect fileSet
      require(intersection.isEmpty, s"duplicate input files: ${intersection}")
      acc union fileSet
    }.toList.sortBy(_.absolutePath)
    log.info(s"Merging ${inputFiles.size} files into '${outFile.absolutePath}'")
    inputFiles.foreach { f =>
      log.info(s" <- ${f.absolutePath}")
    }
    val result = mergeFiles(outFile, inputFiles, log)
    log.info("Merged files, writing output")
    dump(result, outFile)
    log.info("Written output")
  }

  private[this] final def mergeFiles(output: File, inputs: List[File], log: ManagedLogger): ResultFile = {
    val result = inputs.foldLeft(ResultFile.empty) { (acc, input) =>
      val rf = parse(input, log).map(_.withThreadsInParams)
      acc ++ rf
    }
    @tailrec
    def compareByCommonKeys(keys: List[String], x: JsonObject, y: JsonObject): Int = {
      keys match {
        case h :: t =>
          val xv = x(h).flatMap(_.asString).getOrElse("")
          val yv = y(h).flatMap(_.asString).getOrElse("")
          Order[String].compare(xv, yv) match {
            case 0 => compareByCommonKeys(t, x, y)
            case n => n
          }
        case Nil => // all common keys are equal
          0
      }
    }
    val orderParams: Order[JsonObject] = { (x, y) =>
      val xSet = x.keys.toSet
      val ySet = y.keys.toSet
      if (xSet === ySet) { // same keys, compare values
        compareByCommonKeys(xSet.toList.sorted, x, y)
      } else if (xSet subsetOf ySet) { // x < y
        -1
      } else if (ySet subsetOf xSet) { // x > y
        +1
      } else { // key sets incomparable
        val common = xSet intersect ySet
        if (common.isEmpty) {
          // we give up:
          (xSet.## - ySet.##) match {
            case 0 => -1 // hash collision
            case n => n
          }
        } else {
          compareByCommonKeys(common.toList.sorted, x, y) match {
            case 0 =>
              // we give up:
              (xSet.## - ySet.##) match {
                case 0 => -1 // hash collision
                case n => n
              }
            case n =>
              n
          }
        }
      }
    }
    result.sorted(Order.from[BenchmarkResult] { (x, y) =>
      Order[String].compare(x.name, y.name) match {
        case 0 =>
          // same name, order based on threads:
          Order[Int].compare(x.threads, y.threads) match {
            case 0 =>
              // same threads, order based on params:
              orderParams.compare(x.params, y.params) match {
                case 0 =>
                  if (x == y) {
                    0
                  } else {
                    (x.## - y.##) match {
                      case 0 => -1 // hash collision
                      case n => n
                    }
                  }
                case n =>
                  n
              }
            case n =>
              n
          }
        case n =>
          // different name, use that:
          n
      }
    })
  }

  private[this] final def parse(file: File, log: ManagedLogger): ResultFile = {
    val contents = SbtIO.read(file)
    val j: Json = io.circe.parser.parse(contents).getOrElse {
      val msg = s"not a JSON file: ${file.absolutePath}"
      log.err(msg)
      throw new IllegalArgumentException(msg)
    }
    j.as[ResultFile].fold(
      err => {
        log.err(err.toString)
        throw new IllegalArgumentException(err.toString)
      },
      ok => ok
    )
  }

  private[this] final def dump(r: ResultFile, to: File): Unit = {
    val str = Encoder[ResultFile].apply(r).spaces4
    SbtIO.write(to, str)
  }
}

private object Model {

  final type ResultFile = Chain[BenchmarkResult]

  final object ResultFile {
    def empty: ResultFile =
      Chain.empty
  }

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
    jdkVersion: JdkVersion,
    vmName: String,
    vmVersion: JdkVersion,
    warmupIterations: Int,
    warmupTime: String,
    warmupBatchSize: Int,
    measurementIterations: Int,
    measurementTime: String,
    measurementBatchSize: Int,
    params: JsonObject = JsonObject.empty,
    primaryMetric: Metric,
    secondaryMetrics: JsonObject = JsonObject.empty,
  ) {

    def name: String =
      this.benchmark

    def withThreadsInParams: BenchmarkResult = {
      import BenchmarkResult.threadsKey
      require(!this.params.contains(threadsKey), s"params already contains '${threadsKey}'")
      val newParams = (threadsKey -> Json.fromString(this.threads.toString)) +: this.params
      this.copy(params = newParams)
    }
  }

  final object BenchmarkResult {
    private final val threadsKey =
      "_jmh_threads"
  }

  @JsonCodec
  final case class Metric(
    score: Double,
    scoreError: Double,
    scoreConfidence: (Double, Double),
    scorePercentiles: JsonObject,
    scoreUnit: String,
    rawData:  List[List[Double]],
  )

  final case class JdkVersion(version: String) {
    final override def toString: String =
      version
  }

  final object JdkVersion {

    private[this] final val versionPattern =
      raw"^(\d+\.)*(\d+)".r

    implicit val jdkVersionDecoder: Decoder[JdkVersion] = {
      Decoder.decodeString.emap { ver =>
        versionPattern.findFirstIn(ver) match {
          case Some(v) => Right(JdkVersion(v))
          case None => Left(s"invalid version: '${ver}'")
        }
      }
    }

    implicit val jdkVersionEncoder: Encoder[JdkVersion] = {
      Encoder.encodeString.contramap(_.version)
    }
  }
}

private object CleanBenchResults {

  final def cleanBenchResults(dir: File, pat: String, log: ManagedLogger): Unit = {
    println(dir.absolutePath)
    val files = SbtIO.listFiles(dir, FileFilter.globFilter(pat)).toList
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

  final def cleanJdkAndVmVersion(jdkVersion: String, vmVersion: String): Either[String, (String, String)] = {
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
