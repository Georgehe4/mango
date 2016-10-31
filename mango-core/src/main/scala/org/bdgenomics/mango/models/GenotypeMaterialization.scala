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

import net.liftweb.json.Serialization.write
import org.apache.parquet.filter2.dsl.Dsl._
import org.apache.parquet.filter2.predicate.FilterPredicate
import org.apache.spark._
import org.apache.spark.rdd.RDD
import org.bdgenomics.adam.models.{ ReferenceRegion, SequenceDictionary }
import org.bdgenomics.adam.projections.{ GenotypeField, Projection }
import org.bdgenomics.adam.rdd.ADAMContext._
import org.bdgenomics.formats.avro.Genotype
import org.bdgenomics.mango.layout.{ GenotypeJson, VariantJson }

import scala.reflect.ClassTag
import scala.math.{ max, min }

/*
 * Handles loading and tracking of data from persistent storage into memory for Genotype data.
 * @see LazyMaterialization.scala
 */
class GenotypeMaterialization(s: SparkContext,
                              filePaths: List[String],
                              d: SequenceDictionary,
                              parts: Int,
                              chunkS: Int) extends LazyMaterialization[Genotype]("GenotypeRDD")
    with Serializable {

  @transient val sc = s
  @transient implicit val formats = net.liftweb.json.DefaultFormats
  val dict = d
  val partitions = parts
  val sd = d
  val files = filePaths
  val variantPlaceholder = "N"
  def getReferenceRegion = (g: Genotype) => ReferenceRegion(g.getContigName, g.getStart, g.getEnd)
  def load = (region: ReferenceRegion, file: String) => GenotypeMaterialization.load(sc, Some(region), file)

  /**
   * Stringifies data from genotypes to lists of variants and genotypes over the requested regions
   *
   * @param data RDD of  filtered (sampleId, Genotype)
   * @return Map of (key, json) for the ReferenceRegion specified
   * N
   */
  def stringify(data: RDD[(String, Genotype)]): Map[String, String] = {

    val flattened: Map[String, Array[(String, VariantJson)]] = data
      .collect
      .groupBy(_._1)
      .mapValues(v => {
        v.map(r => {
          (r._2.getSampleId, VariantJson(r._2.getContigName, r._2.getStart,
            r._2.getVariant.getReferenceAllele, r._2.getVariant.getAlternateAllele, r._2.getEnd))
        })
      })

    // stringify genotypes and group with variants
    val genotypes: Map[String, VariantAndGenotypes] =
      flattened.mapValues(v => {
        VariantAndGenotypes(v.map(_._2),
          v.groupBy(_._2).mapValues(r => r.map(_._1))
            .map(r => GenotypeJson(r._2, r._1)).toArray)
      })

    // write variants and genotypes to json
    genotypes.mapValues(v => write(v))
  }

  /**
   * Formats raw data from RDD to JSON.
   *
   * @param region Region to obtain coverage for
   * @param binning Tells what granularity of coverage to return. Used for large regions
   * @return JSONified data map
   */
  def getGenotype(region: ReferenceRegion, binning: Int = 1): Map[String, String] = {
    val data: RDD[(String, Genotype)] = get(region)
    if (binning == 1) {
      return stringify(data)
    }
    val flattened: Map[String, Array[(String, VariantJson)]] = data
      .map(r => {
        val geno = r._2
        val bin: Int = (geno.getStart / binning).toInt
        val json = (geno.getSampleId, VariantJson(geno.getContigName, geno.getStart,
          geno.getVariant.getReferenceAllele, geno.getVariant.getAlternateAllele, geno.getEnd))
        ((r._1, bin), json)
      })
      .reduceByKey((a, b) => {
        val end = max(a._2.end, b._2.end)
        val start = min(a._2.position, b._2.position)
        (a._1, VariantJson(a._2.contig, start,
          variantPlaceholder, variantPlaceholder, end))
      })
      .collect
      .groupBy(_._1._1)
      .mapValues(v => {
        v.map(_._2)
      })

    // stringify genotypes and group with variants
    val genotypes: Map[String, VariantAndGenotypes] =
      flattened.mapValues(v => {
        VariantAndGenotypes(v.map(_._2),
          v.groupBy(_._2).mapValues(r => r.map(_._1))
            .map(r => GenotypeJson(r._2, r._1)).toArray)
      })

    // write variants and genotypes to json
    genotypes.mapValues(v => write(v))
  }
}

object GenotypeMaterialization {

  def apply(sc: SparkContext, files: List[String], dict: SequenceDictionary, partitions: Int): GenotypeMaterialization = {
    new GenotypeMaterialization(sc, files, dict, partitions, 100)
  }

  def apply[T: ClassTag, C: ClassTag](sc: SparkContext, files: List[String], dict: SequenceDictionary, partitions: Int, chunkSize: Int): GenotypeMaterialization = {
    new GenotypeMaterialization(sc, files, dict, partitions, chunkSize)
  }

  def load(sc: SparkContext, region: Option[ReferenceRegion], fp: String): RDD[Genotype] = {
    val genotypes: RDD[Genotype] =
      if (fp.endsWith(".adam")) {
        loadAdam(sc, region, fp)
      } else if (fp.endsWith(".vcf")) {
        region match {
          case Some(_) => sc.loadGenotypes(fp).rdd.filter(g => (g.getContigName == region.get.referenceName && g.getStart < region.get.end
            && g.getEnd > region.get.start))
          case None => sc.loadGenotypes(fp).rdd
        }
      } else {
        throw UnsupportedFileException("File type not supported")
      }

    val key = LazyMaterialization.filterKeyFromFile(fp)
    // map unique ids to features to be used in tiles
    genotypes.map(r => {
      if (r.getSampleId == null) new Genotype(r.getVariant, r.getContigName, r.getStart, r.getEnd, r.getVariantCallingAnnotations,
        key, r.getSampleDescription, r.getProcessingDescription, r.getAlleles, r.getExpectedAlleleDosage, r.getReferenceReadDepth,
        r.getAlternateReadDepth, r.getReadDepth, r.getMinReadDepth, r.getGenotypeQuality, r.getGenotypeLikelihoods, r.getNonReferenceLikelihoods, r.getStrandBiasComponents, r.getSplitFromMultiAllelic, r.getIsPhased, r.getPhaseSetId, r.getPhaseQuality)
      else r
    })
  }

  def loadAdam(sc: SparkContext, region: Option[ReferenceRegion], fp: String): RDD[Genotype] = {
    val pred: Option[FilterPredicate] =
      region match {
        case Some(_) => Some(((LongColumn("variant.end") >= region.get.start) && (LongColumn("variant.start") <= region.get.end) && (BinaryColumn("variant.contig.contigName") === region.get.referenceName)))
        case None    => None
      }
    val proj = Projection(GenotypeField.variant, GenotypeField.alleles, GenotypeField.sampleId)
    sc.loadParquetGenotypes(fp, predicate = pred, projection = Some(proj)).rdd
  }

}

case class VariantAndGenotypes(variants: Array[VariantJson], genotypes: Array[GenotypeJson])