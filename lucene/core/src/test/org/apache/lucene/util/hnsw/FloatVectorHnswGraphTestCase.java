/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.lucene.util.hnsw;

import static org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS;

import com.carrotsearch.randomizedtesting.RandomizedTest;
import java.io.IOException;
import java.util.Arrays;

import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.VectorEncoding;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.FixedBitSet;
import org.junit.Before;

/** Tests HNSW KNN graphs */
public abstract class FloatVectorHnswGraphTestCase extends HnswGraphTestCase<float[]> {

  @Before
  public void setup() {
    similarityFunction = RandomizedTest.randomFrom(VectorSimilarityFunction.values());
  }

  @Override
  public VectorEncoding getVectorEncoding() {
    return VectorEncoding.FLOAT32;
  }

  @Override
  Query knnQuery(String field, float[] vector, int k) {
    return new KnnFloatVectorQuery(field, vector, k);
  }

  @Override
  float[] randomVector(int dim) {
    return randomVector(random(), dim);
  }

  @Override
  public AbstractMockVectorValues<float[]> vectorValues(int size, int dimension) {
    return MockVectorValues.fromValues(createRandomFloatVectors(size, dimension, random()));
  }

  @Override
  public AbstractMockVectorValues<float[]> vectorValues(float[][] values) {
    return MockVectorValues.fromValues(values);
  }

  @Override
  public AbstractMockVectorValues<float[]> vectorValues(LeafReader reader, String fieldName)
      throws IOException {
    FloatVectorValues vectorValues = reader.getFloatVectorValues(fieldName);
    float[][] vectors = new float[reader.maxDoc()][];
    while (vectorValues.nextDoc() != NO_MORE_DOCS) {
      vectors[vectorValues.docID()] =
          ArrayUtil.copyOfSubArray(
              vectorValues.vectorValue(), 0, vectorValues.vectorValue().length);
    }
    return MockVectorValues.fromValues(vectors);
  }

  @Override
  public AbstractMockVectorValues<float[]> vectorValues(
      int size,
      int dimension,
      AbstractMockVectorValues<float[]> pregeneratedVectorValues,
      int pregeneratedOffset) {
    float[][] vectors = new float[size][];
    float[][] randomVectors =
        createRandomFloatVectors(
            size - pregeneratedVectorValues.values.length, dimension, random());

    for (int i = 0; i < pregeneratedOffset; i++) {
      vectors[i] = randomVectors[i];
    }

    int currentDoc;
    while ((currentDoc = pregeneratedVectorValues.nextDoc()) != NO_MORE_DOCS) {
      vectors[pregeneratedOffset + currentDoc] = pregeneratedVectorValues.values[currentDoc];
    }

    for (int i = pregeneratedOffset + pregeneratedVectorValues.values.length;
        i < vectors.length;
        i++) {
      vectors[i] = randomVectors[i - pregeneratedVectorValues.values.length];
    }

    return MockVectorValues.fromValues(vectors);
  }

  @Override
  public Field knnVectorField(
      String name, float[] vector, VectorSimilarityFunction similarityFunction) {
    return new KnnFloatVectorField(name, vector, similarityFunction);
  }

  @Override
  RandomAccessVectorValues<float[]> circularVectorValues(int nDoc) {
    return new CircularFloatVectorValues(nDoc);
  }

  @Override
  float[] getTargetVector() {
    return new float[] {1f, 0f};
  }

  public void testSearchWithSkewedAcceptOrds() throws IOException {
    int nDoc = 1000;
    similarityFunction = VectorSimilarityFunction.EUCLIDEAN;
    RandomAccessVectorValues<float[]> vectors = circularVectorValues(nDoc);
    IHnswGraphBuilder<float[]> builder =
        factory.createBuilder(
            vectors, getVectorEncoding(), similarityFunction, 16, 100, random().nextInt());
    HnswGraph hnsw = builder.build(vectors.copy());

    // Skip over half of the documents that are closest to the query vector
    FixedBitSet acceptOrds = new FixedBitSet(nDoc);
    for (int i = 500; i < nDoc; i++) {
      acceptOrds.set(i);
    }
    NeighborQueue nn =
        HnswGraphSearcher.search(
            getTargetVector(),
            10,
            vectors.copy(),
            getVectorEncoding(),
            similarityFunction,
            hnsw,
            acceptOrds,
            Integer.MAX_VALUE);

    int[] nodes = nn.nodes();
    assertEquals("Number of found results is not equal to [10].", 10, nodes.length);
    int sum = 0;
    for (int node : nodes) {
      assertTrue("the results include a deleted document: " + node, acceptOrds.get(node));
      sum += node;
    }
    // We still expect to get reasonable recall. The lowest non-skipped docIds
    // are closest to the query vector: sum(500,509) = 5045
    assertTrue("sum(result docs)=" + sum, sum < 5100);
  }

  public void testBigRandom() throws IOException {
    for (int k = 0; k < 10; k++) {
      int size = atLeast(40000);
      int dim = atLeast(1500);
      AbstractMockVectorValues<float[]> vectors = vectorValues(size, dim);
      int topK = 100;
      IHnswGraphBuilder<float[]> builder =
          factory.createBuilder(
              vectors, getVectorEncoding(), similarityFunction, 10, 30, random().nextLong());
      HnswGraph hnsw = builder.build(vectors.copy());

      for (int i = 0; i < 10; i++) {
        NeighborQueue actual;
        float[] query = randomVector(dim);
        actual =
            HnswGraphSearcher.search(
                query,
                topK,
                vectors,
                getVectorEncoding(),
                similarityFunction,
                hnsw,
                null,
                Integer.MAX_VALUE);

        // pop them to a temp array so we can print them out
        float[] results = new float[topK];
        for (int j = 0; actual.size() > 0; j++) {
          results[j] = actual.topScore();
          actual.pop();
        }

        float lastScore = results[0];
        for (int j = 1; j < results.length; j++) {
          assert results[j] >= lastScore : "results are not sorted at position " + j + " for " + Arrays.toString(results);
          lastScore = results[j];
        }
      }
    }
  }
}
