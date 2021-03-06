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
package org.bdgenomics.mango.converters

import org.bdgenomics.adam.rdd.read.AlignmentRecordRDD
import org.bdgenomics.mango.util.MangoFunSuite
import org.scalatest.FunSuite
import org.bdgenomics.adam.rdd.ADAMContext._
import org.bdgenomics.adam.rdd.feature.FeatureRDD
import org.bdgenomics.adam.rdd.variant.VariantRDD

class GA4GHutilSuite extends MangoFunSuite {
  sparkTest("converting an empty cigar should yield an empty cigar") {
    assert(1 === 1)
  }

  sparkTest("create JSON from AlignmentRecordRDD") {
    val inputPath = resourcePath("small.1.sam")
    val rrdd: AlignmentRecordRDD = sc.loadAlignments(inputPath)
    val collected = rrdd.rdd.collect()

    val json = GA4GHutil.alignmentRecordRDDtoJSON(rrdd).replaceAll("\\s", "")

    val builder = ga4gh.ReadServiceOuterClass.SearchReadsResponse.newBuilder()

    com.google.protobuf.util.JsonFormat.parser().merge(json,
      builder)

    val response = builder.build()

    assert(response.getAlignmentsCount == collected.length)
  }

  sparkTest("create JSON with genotypes from VCF using genotypeRDD") {
    val inputPath = resourcePath("truetest.genotypes.vcf")
    val grdd = sc.loadGenotypes(inputPath)
    val collected = sc.loadVariants(inputPath).rdd.collect()

    val json = GA4GHutil.genotypeRDDtoJSON(grdd)

    val builder = ga4gh.VariantServiceOuterClass
      .SearchVariantsResponse.newBuilder()

    com.google.protobuf.util.JsonFormat.parser().merge(json,
      builder)
    val response = builder.build()

    assert(response.getVariantsCount == collected.length)
  }

  sparkTest("create JSON without genotypes from VCF using variantRDD") {
    val inputPath = resourcePath("truetest.genotypes.vcf")
    val vrdd: VariantRDD = sc.loadVariants(inputPath)
    val collected = vrdd.rdd.collect()

    val json = GA4GHutil.variantRDDtoJSON(vrdd)

    val builder = ga4gh.VariantServiceOuterClass
      .SearchVariantsResponse.newBuilder()

    com.google.protobuf.util.JsonFormat.parser().merge(json,
      builder)
    val response = builder.build()

    assert(response.getVariantsCount == collected.length)
  }

  sparkTest("create JSON from Feature") {
    val inputPath = resourcePath("smalltest.bed")
    val frdd: FeatureRDD = sc.loadBed(inputPath)
    val collected = frdd.rdd.collect()

    val json = GA4GHutil.featureRDDtoJSON(frdd).replaceAll("\\s", "")

    val builder = ga4gh.SequenceAnnotationServiceOuterClass.SearchFeaturesResponse
      .newBuilder()

    com.google.protobuf.util.JsonFormat.parser().merge(json,
      builder)
    val response = builder.build()

    assert(response.getFeaturesCount == collected.length)

  }

}
