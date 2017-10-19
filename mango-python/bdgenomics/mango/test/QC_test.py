#
# Licensed to Big Data Genomics (BDG) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The BDG licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

import sys
from bdgenomics.mango.QC import CoverageDistribution
from bdgenomics.mango.test import SparkTestCase
from collections import Counter

from bdgenomics.adam.adamContext import ADAMContext


class QCTest(SparkTestCase):

    def test_coverage_distribution(self):
        # load file
        ac = ADAMContext(self.sc)
        testFile = self.resourceFile("small.sam")
        # read alignments

        reads = ac.loadAlignments(testFile)

        # convert to coverage
        coverage = reads.toCoverage()

        qc = CoverageDistribution(self.sc, coverage)

        cd = qc.plot(testMode = True)

        assert(len(cd) == 1)
        assert(cd.pop()[1] == 1500)


    def test_normalized_coverage_distribution(self):
        # load file
        ac = ADAMContext(self.sc)
        testFile = self.resourceFile("small.sam")
        # read alignments

        reads = ac.loadAlignments(testFile)

        # convert to coverage
        coverage = reads.toCoverage()

        qc = CoverageDistribution(self.sc, coverage)

        cd = qc.plot(testMode = True, normalize = True)

        assert(len(cd) == 1)
        assert(cd.pop()[1] == 1)


    def test_cumulative_coverage_distribution(self):
        # load file
        ac = ADAMContext(self.sc)
        testFile = self.resourceFile("small.sam")
        # read alignments

        reads = ac.loadAlignments(testFile)

        # convert to coverage
        coverage = reads.toCoverage()

        qc = CoverageDistribution(self.sc, coverage)

        cd = qc.plot(testMode = True, cumulative = True)

        assert(len(cd) == 1)
        assert(cd.pop()[1] == 1500)


    def test_example_alignments(self):
        # load file
        ac = ADAMContext(self.sc)
        testFile = self.exampleFile("chr17.7500000-7515000.sam.adam")
        # read alignments

        reads = ac.loadAlignments(testFile)

        # convert to coverage
        coverage = reads.toCoverage()

        qc = CoverageDistribution(self.sc, coverage)

        cd = qc.plot(testMode = True, cumulative = True)

        assert(len(cd) == 1)
        assert(cd.pop()[1] == 6.0)


    def test_example_coverage(self):
        # load file
        ac = ADAMContext(self.sc)
        testFile = self.exampleFile("chr17.7500000-7515000.sam.coverage.adam")
        # read alignments

        coverage = ac.loadCoverage(testFile)

        qc = CoverageDistribution(self.sc, coverage)

        cd1 = qc.plot(testMode = True, cumulative = True)

        assert(len(cd1) == 1)
        x = cd1.pop()
        assert(x[1] == 6)
        assert(x[2] == 38)

        cd2 = qc.plot(testMode = True, cumulative = False)

        assert(len(cd2) == 1)
        x = cd2.pop()
        assert(x[1] == 6)
        assert(x[2] == 32)

        cd3 = qc.plot(testMode = True, cumulative = True, normalize = True)
        total = float(sum(qc.collectedCoverage[0].values()))

        assert(len(cd3) == 1)
        x = cd3.pop()
        assert(x[1] == 6.0/total)
        assert(x[2] == 38.0/total)

        cd4 = qc.plot(testMode = True, normalize = True)

        assert(len(cd4) == 1)
        x = cd4.pop()
        assert(x[1] == 6.0/total)
        assert(x[2] == 32.0/total)


    def test_example(self):
        # these variables are read into mango-python.py
        sc = self.sc
        testMode = True
        coverageFile = self.exampleFile("chr17.7500000-7515000.sam.coverage.adam")

        # this file is converted from ipynb in make test
        testFile = self.exampleFile("mango-python.py")
        execfile(testFile)

