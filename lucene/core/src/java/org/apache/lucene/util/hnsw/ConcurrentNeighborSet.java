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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.PrimitiveIterator;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.FixedBitSet;

import static org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS;

/** A concurrent set of neighbors. */
public class ConcurrentNeighborSet {
  /** the node id whose neighbors we are storing */
  private final int nodeId;

  /**
   * We use a copy-on-write NeighborArray to store the neighbors. Even though updating this is
   * expensive, it is still faster than using a concurrent Collection because "iterate through a
   * node's neighbors" is a hot loop in adding to the graph, and NeighborArray can do that much
   * faster: no boxing/unboxing, all the data is stored sequentially instead of having to follow
   * references, and no fancy encoding necessary for node/score.
   */
  private final AtomicReference<ConcurrentNeighborArray> neighborsRef;
  private final float alpha;

  private final NeighborSimilarity similarity;

  /** the maximum number of neighbors we can store */
  private final int maxConnections;

  public ConcurrentNeighborSet(int nodeId, int maxConnections, NeighborSimilarity similarity, float alpha) {
    this.nodeId = nodeId;
    this.maxConnections = maxConnections;
    this.similarity = similarity;
    neighborsRef = new AtomicReference<>(new ConcurrentNeighborArray(maxConnections, true));
    this.alpha = alpha;
  }

  public ConcurrentNeighborSet(int nodeId, int maxConnections, NeighborSimilarity similarity) {
    this(nodeId, maxConnections, similarity, 1.0f);
  }

  private ConcurrentNeighborSet(ConcurrentNeighborSet old) {
    this.nodeId = old.nodeId;
    this.maxConnections = old.maxConnections;
    this.similarity = old.similarity;
    this.alpha = old.alpha;
    neighborsRef = new AtomicReference<>(old.neighborsRef.get());
  }

  public PrimitiveIterator.OfInt nodeIterator() {
    // don't use a stream here. stream's implementation of iterator buffers
    // very aggressively, which is a big waste for a lot of searches.
    return new NeighborIterator(neighborsRef.get());
  }

  public void backlink(Function<Integer, ConcurrentNeighborSet> neighborhoodOf) throws IOException {
    NeighborArray neighbors = neighborsRef.get();
    for (int i = 0; i < neighbors.size(); i++) {
      int nbr = neighbors.node[i];
      float nbrScore = neighbors.score[i];
      ConcurrentNeighborSet nbrNbr = neighborhoodOf.apply(nbr);
      nbrNbr.insert(nodeId, nbrScore);
    }
  }

  private static class NeighborIterator implements PrimitiveIterator.OfInt {
    private final NeighborArray neighbors;
    private int i;

    private NeighborIterator(NeighborArray neighbors) {
      this.neighbors = neighbors;
      i = 0;
    }

    @Override
    public boolean hasNext() {
      return i < neighbors.size();
    }

    @Override
    public int nextInt() {
      return neighbors.node[i++];
    }
  }

  public int size() {
    return neighborsRef.get().size();
  }

  public int arrayLength() {
    return neighborsRef.get().node.length;
  }

  /**
   * For each candidate (going from best to worst), select it only if it is closer to target than it
   * is to any of the already-selected candidates. This is maintained whether those other neighbors
   * were selected by this method, or were added as a "backlink" to a node inserted concurrently
   * that chose this one as a neighbor.
   */
  public void insertDiverse(INeighborArray candidates) {
    BitSet selected = new FixedBitSet(candidates.size());
    int nSelected = 0;
    var scores = new ScoreCache();
    for (float a = 1.0f; a <= alpha && nSelected < maxConnections; a += 0.2) {
      for (int i = candidates.size() - 1; i >= 0; i--) {
        if (selected.get(i)) {
          continue;
        }

        int cNode = candidates.node()[i];
        float cScore = candidates.score()[i];
        if (isDiverse(cNode, cScore, candidates, selected, a, scores)) {
          selected.set(i);
          nSelected++;
        }
      }
    }
    insertMultiple(candidates, selected, scores);
  }

  public ConcurrentNeighborArray getCurrent() {
    return neighborsRef.get();
  }

  static NeighborArray mergeCandidates(INeighborArray a1, NeighborArray a2) {
    assert a1.scoresDescending();
    assert a2.scoresDescending();

    NeighborArray merged = new NeighborArray(a1.size() + a2.size(), true);
    int i = 0, j = 0;

    while (i < a1.size() && j < a2.size()) {
      if (a1.score()[i] < a2.score[j]) {
        merged.addInOrder(a2.node[j], a2.score[j]);
        j++;
      } else if (a1.score()[i] > a2.score[j]) {
        merged.addInOrder(a1.node()[i], a1.score()[i]);
        i++;
      } else {
        merged.addInOrder(a1.node()[i], a1.score()[i]);
        if (a2.node[j] != a1.node()[i]) {
          merged.addInOrder(a2.node[j], a2.score[j]);
        }
        i++;
        j++;
      }
    }

    // If elements remain in a1, add them
    while (i < a1.size()) {
      // Skip duplicates between the remaining elements in a1 and the last added element in a2
      if (j > 0 && i < a1.size() && a1.node()[i] == a2.node[j-1]) {
        i++;
        continue;
      }
      merged.addInOrder(a1.node()[i], a1.score()[i]);
      i++;
    }

    // If elements remain in a2, add them
    while (j < a2.size()) {
      // Skip duplicates between the remaining elements in a2 and the last added element in a1
      if (i > 0 && j < a2.size() && a2.node[j] == a1.node()[i-1]) {
        j++;
        continue;
      }
      merged.addInOrder(a2.node[j], a2.score[j]);
      j++;
    }

    // TODO fixme
    var m2 = new NeighborArray(merged.size(), false);
    for (int k = merged.size() - 1; k >= 0; k--) {
      m2.addInOrder(merged.node[k], merged.score[k]);
    }
    return m2;
  }

  private void insertMultiple(INeighborArray others, BitSet selected, ScoreCache scores) {
    neighborsRef.getAndUpdate(
        current -> {
          ConcurrentNeighborArray next = current.copy();
          for (int i = others.size() - 1; i >= 0; i--) {
            if (!selected.get(i)) {
              continue;
            }
            int node = others.node()[i];
            float score = others.score()[i];
            next.insertSorted(node, score);
          }
          enforceMaxConnLimit(next, 1.0f, scores);
          return next;
        });
  }

  /**
   * Insert a new neighbor, maintaining our size cap by removing the least diverse neighbor if
   * necessary.
   */
  public void insert(int neighborId, float score, float alpha) throws IOException {
    assert neighborId != nodeId : "can't add self as neighbor at node " + nodeId;
    neighborsRef.getAndUpdate(
        current -> {
          ConcurrentNeighborArray next = current.copy();
          next.insertSorted(neighborId, score);
          enforceMaxConnLimit(next, alpha, null);
          return next;
        });
  }

  public void insert(int neighborId, float score) throws IOException {
    insert(neighborId, score, 1.0f);
  }

  private static class ScoreCache {
    private final Map<Long, Float> scores = new HashMap<>();

    public float get(int node, int other, NeighborSimilarity.ScoreFunction scoreProvider) {
      long key = (((long) node) << 32) | (0xFFFFFFFFL & other);
      Float score = scores.get(key);
      if (score == null) {
        score = scoreProvider.apply(other);
        scores.put(key, score);
      }
      return score;
    }
  }

  // is the candidate node with the given score closer to the base node than it is to any of the
  // existing neighbors
  private boolean isDiverse(int node, float score, INeighborArray others, BitSet selected, float alpha, ScoreCache scores) {
    if (others.size() == 0) {
      return true;
    }

    NeighborSimilarity.ScoreFunction scoreProvider = similarity.scoreProvider(node);
    for (int i = selected.nextSetBit(0); i != NO_MORE_DOCS; i = selected.nextSetBit(i + 1)) {
      int otherNode = others.node()[i];
      if (node == otherNode) {
        break;
      }
      if (scores.get(node, i, scoreProvider) > score * alpha) {
        return false;
      }

      // nextSetBit will error out if we're at the end of the bitset, so check this manually
      if (i + 1 >= selected.length()) {
        break;
      }
    }
    return true;
  }

  private void enforceMaxConnLimit(NeighborArray neighbors, float alpha, ScoreCache scores) {
    while (neighbors.size() > maxConnections) {
      try {
        removeLeastDiverse(neighbors, alpha, scores);
      } catch (IOException e) {
        throw new UncheckedIOException(e); // called from closures
      }
    }
  }

  /**
   * For each node e1 starting with the last neighbor (i.e. least similar to the base node), look at
   * all nodes e2 that are closer to the base node than e1 is. If any e2 is closer to e1 than e1 is
   * to the base node, remove e1.
   */
  private void removeLeastDiverse(NeighborArray neighbors, float alpha, ScoreCache scores) throws IOException {
    for (int i = neighbors.size() - 1; i >= 1; i--) {
      int e1Id = neighbors.node[i];
      float baseScore = neighbors.score[i];
      NeighborSimilarity.ScoreFunction scoreProvider = similarity.scoreProvider(e1Id);

      for (int j = i - 1; j >= 0; j--) {
        int n2Id = neighbors.node[j];
        float n1n2Score = scores == null ? scoreProvider.apply(n2Id) : scores.get(e1Id, n2Id, scoreProvider);
        if (n1n2Score > baseScore * alpha) {
          neighbors.removeIndex(i);
          return;
        }
      }
    }

    // couldn't find any "non-diverse" neighbors, so remove the one farthest from the base node
    neighbors.removeIndex(neighbors.size() - 1);
  }

  public ConcurrentNeighborSet copy() {
    return new ConcurrentNeighborSet(this);
  }

  /** Only for testing; this is a linear search */
  boolean contains(int i) {
    var it = this.nodeIterator();
    while (it.hasNext()) {
      if (it.nextInt() == i) {
        return true;
      }
    }
    return false;
  }

  /** Encapsulates comparing node distances for diversity checks. */
  public interface NeighborSimilarity {
    /** for one-off comparisons between nodes */
    float score(int node1, int node2);

    /**
     * For when we're going to compare node1 with multiple other nodes. This allows us to skip
     * loading node1's vector (potentially from disk) redundantly for each comparison.
     */
    ScoreFunction scoreProvider(int node1);

    /**
     * A Function&lt;Integer, Float&gt; without the boxing
     */
    @FunctionalInterface
    public interface ScoreFunction {
      float apply(int node);
    }
  }

  /** A NeighborArray that knows how to copy itself and that checks for duplicate entries */
  static class ConcurrentNeighborArray extends NeighborArray {
    public ConcurrentNeighborArray(int maxSize, boolean descOrder) {
      super(maxSize, descOrder);
    }

    // two nodes may attempt to add each other in the Concurrent classes,
    // so we need to check if the node is already present.  this means that we can't use
    // the parent approach of "append it, and then move it into place"
    @Override
    public void insertSorted(int newNode, float newScore) {
      if (size == node.length) {
        growArrays();
      }
      int insertionPoint =
          scoresDescOrder
              ? descSortFindRightMostInsertionPoint(newScore, size)
              : ascSortFindRightMostInsertionPoint(newScore, size);
      if (!duplicateExistsNear(insertionPoint, newNode, newScore)) {
        System.arraycopy(node, insertionPoint, node, insertionPoint + 1, size - insertionPoint);
        System.arraycopy(score, insertionPoint, score, insertionPoint + 1, size - insertionPoint);
        node[insertionPoint] = newNode;
        score[insertionPoint] = newScore;
        ++size;
      }
    }

    private boolean duplicateExistsNear(int insertionPoint, int newNode, float newScore) {
      // Check to the left
      for (int i = insertionPoint - 1; i >= 0 && score[i] == newScore; i--) {
        if (node[i] == newNode) {
          return true;
        }
      }

      // Check to the right
      for (int i = insertionPoint; i < size && score[i] == newScore; i++) {
        if (node[i] == newNode) {
          return true;
        }
      }

      return false;
    }

    public ConcurrentNeighborArray copy() {
      ConcurrentNeighborArray copy = new ConcurrentNeighborArray(node.length, scoresDescOrder);
      copy.size = size;
      System.arraycopy(node, 0, copy.node, 0, size);
      System.arraycopy(score, 0, copy.score, 0, size);
      return copy;
    }
  }
}
