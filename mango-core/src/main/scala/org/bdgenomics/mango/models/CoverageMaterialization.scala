/**
 * Licensed to Big Data Genomics (BDG) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The BDG licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.bdgenomics.mango.models

import java.io.{ FileNotFoundException, File }

import net.liftweb.json.Serialization.write
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.bdgenomics.adam.models.{ Coverage, ReferenceRegion, SequenceDictionary }
import org.bdgenomics.adam.projections.{ Projection }
import org.bdgenomics.adam.rdd.ADAMContext._
import org.bdgenomics.adam.rdd.features.CoverageRDD
import org.bdgenomics.mango.layout.PositionCount
import org.bdgenomics.utils.misc.Logging
import org.ga4gh.{ GAReadAlignment, GASearchReadsResponse }
import net.liftweb.json.Serialization._

/**
 *
 * @param s SparkContext
 * @param dict Sequence Dictionay calculated from reference
 * extends LazyMaterialization and KTiles
 * @see LazyMaterialization
 * @see KTiles
 */
class CoverageMaterialization(s: SparkContext,
                              filePaths: List[String],
                              dict: SequenceDictionary) extends LazyMaterialization[Coverage]
    with Serializable with Logging {

  @transient implicit val formats = net.liftweb.json.DefaultFormats
  @transient val sc = s
  val sd = dict
  val files = filePaths

  def load = (region: ReferenceRegion, file: String) => CoverageRecordMaterialization.load(sc, region, file).rdd

  /**
   * Extracts ReferenceRegion from CoverageRecord
   * @param ar CoverageRecord
   * @return extracted ReferenceRegion
   */
  def getReferenceRegion = (ar: Coverage) => ReferenceRegion(ar)

  /**
   * Gets Frequency over a given region for each specified sample
   *
   * @param region: ReferenceRegion to query over
   *
   * @return Map[String, Iterable[FreqJson]] Map of [SampleId, Iterable[FreqJson] which stores each base and its
   * cooresponding frequency.
   */
  def getCoverage(region: ReferenceRegion): Map[String, String] = {
    val covCounts: RDD[(String, PositionCount)] =
      get(region)
        .flatMap(r => {
          val t: List[Long] = List.range(r._2.start, r._2.end)
          t.map(n => ((ReferenceRegion(r._2.contigName, n, n + 1), r._1), 1))
            .filter(_._1._1.overlaps(region)) // filter out read fragments not overlapping region
        }).reduceByKey(_ + _) // reduce coverage by combining adjacent frequenct
        .map(r => (r._1._2, PositionCount(r._1._1.start, r._2)))

    covCounts.collect.groupBy(_._1) // group by sample Id
      .map(r => (r._1, write(r._2.map(_._2))))
  }

  /**
   * Formats raw data from KLayeredTile to JSON. This is required by KTiles
   * @param data RDD of (id, AlignmentRecord) tuples
   * @return JSONified data
   */
  def stringify(data: RDD[(String, Coverage)]): Map[String, String] = {
    val flattened: Map[String, Array[PositionCount]] = data
      .collect
      .groupBy(_._1)
      .map(r => (r._1, r._2.map(_._2)))
      .mapValues(r => r.map(f => PositionCount(f.start, f.count.toInt)))

    flattened.mapValues(r => write(r))
  }
}

object CoverageRecordMaterialization {

  def apply(sc: SparkContext, files: List[String], sd: SequenceDictionary): CoverageMaterialization = {
    new CoverageMaterialization(sc, files, sd)
  }

  /**
   * Loads alignment data from ADAM file formats
   * @param sc SparkContext
   * @param fp filepath to load from
   * @return RDD of data from the file over specified ReferenceRegion
   */
  def load(sc: SparkContext, region: ReferenceRegion, fp: String): CoverageRDD = {
    if (fp.endsWith(".adam")) loadAdam(sc, region, fp)
    else {
      throw UnsupportedFileException("File type not supported")
    }
  }
  /**
   * Loads ADAM data using predicate pushdowns
   * @param sc SparkContext
   * @param region Region to load
   * @param fp filepath to load from
   * @return RDD of data from the file over specified ReferenceRegion
   */
  def loadAdam(sc: SparkContext, region: ReferenceRegion, fp: String): CoverageRDD = {
    sc.loadCoverage(fp)
    /*val name = Binary.fromString(region.referenceName)
    val pred: FilterPredicate = ((LongColumn("end") >= region.start) && (LongColumn("start") <= region.end) && (BinaryColumn("contigName") === name) && (BooleanColumn("readMapped") === true))
    val proj = Projection(CoverageRecordField.contigName, CoverageRecordField.mapq, CoverageRecordField.readName, CoverageRecordField.start, CoverageRecordField.readMapped,
      CoverageRecordField.end, CoverageRecordField.sequence, CoverageRecordField.cigar, CoverageRecordField.readNegativeStrand, CoverageRecordField.readPaired, CoverageRecordField.recordGroupSample)
    sc.loadParquetAlignments(fp, predicate = Some(pred), projection = None)*/
  }
}
