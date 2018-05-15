package io.improbable.keanu.algorithms.mcmc;

import io.improbable.keanu.algorithms.NetworkSamples;
import io.improbable.keanu.algorithms.graphtraversal.VertexValuePropagation;
import io.improbable.keanu.network.BayesNet;
import io.improbable.keanu.vertices.Vertex;
import io.improbable.keanu.vertices.dbl.DoubleVertex;
import io.improbable.keanu.vertices.dbl.nonprobabilistic.diff.LogProbGradient;

import java.util.*;

/**
 * Algorithm 3: "Efficient NUTS".
 * The No-U-Turn Sampler: Adaptively Setting Path Lengths in Hamiltonian Monte Carlo
 * https://arxiv.org/pdf/1111.4246.pdf
 */
public class NUTS {

    private final static double DELTA_MAX = 1000.0;

    private NUTS() {
    }

    public static NetworkSamples getPosteriorSamples(final BayesNet bayesNet,
                                                     final List<DoubleVertex> fromVertices,
                                                     final int sampleCount,
                                                     final double stepSize) {

        return getPosteriorSamples(bayesNet, fromVertices, sampleCount, stepSize, Vertex.getDefaultRandom());
    }

    public static NetworkSamples getPosteriorSamples(final BayesNet bayesNet,
                                                     final List<? extends Vertex> sampleFromVertices,
                                                     final int sampleCount,
                                                     final double epsilon,
                                                     final Random random) {

        final List<Vertex<Double>> latentVertices = bayesNet.getContinuousLatentVertices();
        final Map<Long, Long> latentSetAndCascadeCache = VertexValuePropagation.exploreSetting(latentVertices);
        final List<Vertex> probabilisticVertices = bayesNet.getLatentAndObservedVertices();

        final Map<Long, List<?>> samples = new HashMap<>();
        addSampleFromCache(samples, takeSample(sampleFromVertices));

        Map<Long, Double> position = new HashMap<>();
        cachePosition(latentVertices, position);

        Map<Long, Double> gradient = LogProbGradient.getJointLogProbGradientWrtLatents(
                probabilisticVertices
        );

        Map<Long, Double> momentum = new HashMap<>();

        double initialLogOfMasterP = getLogProb(probabilisticVertices);

        BuiltTree tree = new BuiltTree(
                position,
                gradient,
                momentum,
                position,
                gradient,
                momentum,
                position,
                gradient,
                initialLogOfMasterP,
                takeSample(sampleFromVertices),
                1,
                true
        );

        for (int sampleNum = 1; sampleNum < sampleCount; sampleNum++) {

            initializeMomentumForEachVertex(latentVertices, tree.momentumForward, random);
            cache(tree.momentumForward, tree.momentumBackward);

            double u = random.nextDouble() * Math.exp(tree.logOfMasterPAtAcceptedPosition - 0.5 * dotProduct(tree.momentumForward));

            int treeHeight = 0;
            tree.shouldContinueFlag = true;
            tree.acceptedLeapfrogCount = 1;

            while (tree.shouldContinueFlag) {

                //build tree direction -1 = backwards OR 1 = forwards
                int buildDirection = random.nextBoolean() ? 1 : -1;

                BuiltTree otherHalfTree = buildOtherHalfOfTree(
                        tree,
                        latentVertices,
                        latentSetAndCascadeCache,
                        probabilisticVertices,
                        sampleFromVertices,
                        u,
                        buildDirection,
                        treeHeight,
                        epsilon,
                        random
                );

                if (otherHalfTree.shouldContinueFlag) {
                    final double acceptanceProb = (double) otherHalfTree.acceptedLeapfrogCount / tree.acceptedLeapfrogCount;

                    acceptOtherPositionWithProbability(
                            acceptanceProb,
                            tree, otherHalfTree,
                            random
                    );
                }

                tree.acceptedLeapfrogCount += otherHalfTree.acceptedLeapfrogCount;

                tree.shouldContinueFlag = otherHalfTree.shouldContinueFlag && isNotUTurning(
                        tree.positionForward,
                        tree.positionBackward,
                        tree.momentumForward,
                        tree.momentumBackward
                );

                treeHeight++;
            }

            tree.positionForward = tree.acceptedPosition;
            tree.gradientForward = tree.gradientAtAcceptedPosition;
            tree.positionBackward = tree.acceptedPosition;
            tree.gradientBackward = tree.gradientAtAcceptedPosition;

            addSampleFromCache(samples, tree.sampleAtAcceptedPosition);
        }

        return new NetworkSamples(samples, sampleCount);
    }

    private static BuiltTree buildOtherHalfOfTree(BuiltTree currentTree,
                                                  List<Vertex<Double>> latentVertices,
                                                  final Map<Long, Long> latentSetAndCascadeCache,
                                                  List<Vertex> probabilisticVertices,
                                                  final List<? extends Vertex> sampleFromVertices,
                                                  double u,
                                                  int buildDirection,
                                                  int treeHeight,
                                                  double epsilon,
                                                  Random random) {

        BuiltTree otherHalfTree;
        if (buildDirection == -1) {

            otherHalfTree = buildTree(
                    latentVertices,
                    latentSetAndCascadeCache,
                    probabilisticVertices,
                    sampleFromVertices,
                    currentTree.positionBackward,
                    currentTree.gradientBackward,
                    currentTree.momentumBackward,
                    u,
                    buildDirection,
                    treeHeight,
                    epsilon,
                    random
            );

            currentTree.positionBackward = otherHalfTree.positionBackward;
            currentTree.momentumBackward = otherHalfTree.momentumBackward;
            currentTree.gradientBackward = otherHalfTree.gradientBackward;

        } else {

            otherHalfTree = buildTree(
                    latentVertices,
                    latentSetAndCascadeCache,
                    probabilisticVertices,
                    sampleFromVertices,
                    currentTree.positionForward,
                    currentTree.gradientForward,
                    currentTree.momentumForward,
                    u,
                    buildDirection,
                    treeHeight,
                    epsilon,
                    random
            );

            currentTree.positionForward = otherHalfTree.positionForward;
            currentTree.momentumForward = otherHalfTree.momentumForward;
            currentTree.gradientForward = otherHalfTree.gradientForward;
        }

        return otherHalfTree;
    }

    private static BuiltTree buildTree(List<Vertex<Double>> latentVertices,
                                       final Map<Long, Long> latentSetAndCascadeCache,
                                       List<Vertex> probabilisticVertices,
                                       final List<? extends Vertex> sampleFromVertices,
                                       Map<Long, Double> position,
                                       Map<Long, Double> gradient,
                                       Map<Long, Double> momentum,
                                       double u,
                                       int buildDirection,
                                       int treeHeight,
                                       double epsilon,
                                       Random random) {
        if (treeHeight == 0) {

            //Base case—take one leapfrog step in the build direction

            return builtTreeBaseCase(latentVertices,
                    latentSetAndCascadeCache,
                    probabilisticVertices,
                    sampleFromVertices,
                    position,
                    gradient,
                    momentum,
                    u,
                    buildDirection,
                    epsilon
            );

        } else {
            //Recursion—implicitly build the left and right subtrees.

            BuiltTree tree = buildTree(
                    latentVertices,
                    latentSetAndCascadeCache,
                    probabilisticVertices,
                    sampleFromVertices,
                    position,
                    gradient,
                    momentum,
                    u,
                    buildDirection,
                    treeHeight - 1,
                    epsilon,
                    random
            );

            //Should continue building other half if first half's shouldContinueFlag is true
            if (tree.shouldContinueFlag) {

                BuiltTree otherHalfTree = buildOtherHalfOfTree(
                        tree,
                        latentVertices,
                        latentSetAndCascadeCache,
                        probabilisticVertices,
                        sampleFromVertices,
                        u,
                        buildDirection,
                        treeHeight - 1,
                        epsilon,
                        random
                );

                double acceptOtherTreePositionProbability = (double) otherHalfTree.acceptedLeapfrogCount / (tree.acceptedLeapfrogCount + otherHalfTree.acceptedLeapfrogCount);

                acceptOtherPositionWithProbability(
                        acceptOtherTreePositionProbability,
                        tree, otherHalfTree,
                        random
                );

                tree.shouldContinueFlag = otherHalfTree.shouldContinueFlag && isNotUTurning(
                        tree.positionForward,
                        tree.positionBackward,
                        tree.momentumForward,
                        tree.momentumBackward
                );

                tree.acceptedLeapfrogCount += otherHalfTree.acceptedLeapfrogCount;
            }

            return tree;
        }

    }

    private static BuiltTree builtTreeBaseCase(List<Vertex<Double>> latentVertices,
                                               final Map<Long, Long> latentSetAndCascadeCache,
                                               List<Vertex> probabilisticVertices,
                                               final List<? extends Vertex> sampleFromVertices,
                                               Map<Long, Double> position,
                                               Map<Long, Double> gradient,
                                               Map<Long, Double> momentum,
                                               double u,
                                               int buildDirection,
                                               double epsilon) {

        LeapFrogged leapfrog = leapfrog(
                latentVertices,
                latentSetAndCascadeCache,
                probabilisticVertices,
                position,
                gradient,
                momentum,
                epsilon * buildDirection
        );

        final double logOfMasterPAfterLeapfrog = getLogProb(probabilisticVertices);

        final double logOfMasterPMinusMomentum = logOfMasterPAfterLeapfrog - 0.5 * dotProduct(leapfrog.momentum);
        final int acceptedLeapfrogCount = u <= Math.exp(logOfMasterPMinusMomentum) ? 1 : 0;
        final boolean shouldContinueFlag = u < Math.exp(DELTA_MAX + logOfMasterPMinusMomentum);

        final Map<Long, ?> sampleAtAcceptedPosition = takeSample(sampleFromVertices);

        return new BuiltTree(
                leapfrog.position,
                leapfrog.gradient,
                leapfrog.momentum,
                leapfrog.position,
                leapfrog.gradient,
                leapfrog.momentum,
                leapfrog.position,
                leapfrog.gradient,
                logOfMasterPAfterLeapfrog,
                sampleAtAcceptedPosition,
                acceptedLeapfrogCount,
                shouldContinueFlag
        );
    }

    private static double getLogProb(List<Vertex> probabilisticVertices) {
        double sum = 0.0;
        for (Vertex<?> vertex : probabilisticVertices) {
            sum += vertex.logProbAtValue();
        }
        return sum;
    }

    private static void acceptOtherPositionWithProbability(double probability,
                                                           BuiltTree tree,
                                                           BuiltTree otherTree,
                                                           Random random) {
        if (withProbability(probability, random)) {
            tree.acceptedPosition = otherTree.acceptedPosition;
            tree.gradientAtAcceptedPosition = otherTree.gradientAtAcceptedPosition;
            tree.logOfMasterPAtAcceptedPosition = otherTree.logOfMasterPAtAcceptedPosition;
            tree.sampleAtAcceptedPosition = otherTree.sampleAtAcceptedPosition;
        }
    }

    private static boolean withProbability(double probability, Random random) {
        return random.nextDouble() < probability;
    }

    private static boolean isNotUTurning(Map<Long, Double> positionForward,
                                         Map<Long, Double> positionBackward,
                                         Map<Long, Double> momentumForward,
                                         Map<Long, Double> momentumBackward) {
        double forward = 0.0;
        double backward = 0.0;

        for (Map.Entry<Long, Double> forwardPositionForLatent : positionForward.entrySet()) {

            final long latentId = forwardPositionForLatent.getKey();
            final double forwardMinusBackward = forwardPositionForLatent.getValue() - positionBackward.get(latentId);

            forward += forwardMinusBackward * momentumForward.get(latentId);
            backward += forwardMinusBackward * momentumBackward.get(latentId);
        }

        return (forward >= 0.0) && (backward >= 0.0);
    }

    private static void cachePosition(List<Vertex<Double>> latentVertices, Map<Long, Double> position) {
        for (Vertex<Double> vertex : latentVertices) {
            position.put(vertex.getId(), vertex.getValue());
        }
    }

    private static void initializeMomentumForEachVertex(List<Vertex<Double>> vertices,
                                                        Map<Long, Double> momentums,
                                                        Random random) {
        for (Vertex<Double> vertex : vertices) {
            momentums.put(vertex.getId(), random.nextGaussian());
        }
    }

    private static void cache(Map<Long, Double> from, Map<Long, Double> to) {
        for (Map.Entry<Long, Double> entry : from.entrySet()) {
            to.put(entry.getKey(), entry.getValue());
        }
    }

    private static LeapFrogged leapfrog(final List<Vertex<Double>> latentVertices,
                                        final Map<Long, Long> latentSetAndCascadeCache,
                                        final List<Vertex> probabilisticVertices,
                                        final Map<Long, Double> position,
                                        final Map<Long, Double> gradient,
                                        final Map<Long, Double> momentum,
                                        final double epsilon) {

        final double halfTimeStep = epsilon / 2.0;

        Map<Long, Double> nextMomentum = new HashMap<>();
        Map<Long, Double> nextPosition = new HashMap<>();

        for (Map.Entry<Long, Double> rEntry : momentum.entrySet()) {
            final double updatedMomentum = rEntry.getValue() + halfTimeStep * gradient.get(rEntry.getKey());
            nextMomentum.put(rEntry.getKey(), updatedMomentum);
        }

        for (Vertex<Double> latent : latentVertices) {
            final double nextPositionForLatent = position.get(latent.getId()) + halfTimeStep * nextMomentum.get(latent.getId());
            nextPosition.put(latent.getId(), nextPositionForLatent);
            latent.setValue(nextPositionForLatent);
        }

        VertexValuePropagation.cascadeUpdate(latentVertices, latentSetAndCascadeCache);

        Map<Long, Double> nextPositionGradient = LogProbGradient.getJointLogProbGradientWrtLatents(
                probabilisticVertices
        );

        for (Map.Entry<Long, Double> nextMomentumForLatent : nextMomentum.entrySet()) {
            final double nextNextMomentumForLatent = nextMomentumForLatent.getValue() + halfTimeStep * nextPositionGradient.get(nextMomentumForLatent.getKey());
            nextMomentum.put(nextMomentumForLatent.getKey(), nextNextMomentumForLatent);
        }

        return new LeapFrogged(nextPosition, nextMomentum, nextPositionGradient);
    }

    private static double dotProduct(Map<Long, Double> momentums) {
        double dotProduct = 0.0;
        for (Double momentum : momentums.values()) {
            dotProduct += momentum * momentum;
        }
        return dotProduct;
    }

    /**
     * This is meant to be used for tracking a sample while building tree.
     *
     * @param sampleFromVertices take samples from these vertices
     */
    private static Map<Long, ?> takeSample(List<? extends Vertex> sampleFromVertices) {
        Map<Long, ?> sample = new HashMap<>();
        for (Vertex vertex : sampleFromVertices) {
            putValue(vertex, sample);
        }
        return sample;
    }

    private static <T> void putValue(Vertex<T> vertex, Map<Long, ?> target) {
        ((Map<Long, T>) target).put(vertex.getId(), vertex.getValue());
    }

    /**
     * This is used to save of the sample from the uniformly chosen acceptedPosition position
     *
     * @param samples      samples taken already
     * @param cachedSample a cached sample from before leapfrog
     */
    private static void addSampleFromCache(Map<Long, List<?>> samples, Map<Long, ?> cachedSample) {
        for (Map.Entry<Long, ?> sampleEntry : cachedSample.entrySet()) {
            addSampleForVertex(sampleEntry.getKey(), sampleEntry.getValue(), samples);
        }
    }

    private static <T> void addSampleForVertex(long id, T value, Map<Long, List<?>> samples) {
        List<T> samplesForVertex = (List<T>) samples.computeIfAbsent(id, v -> new ArrayList<T>());
        samplesForVertex.add(value);
    }

    private static class LeapFrogged {
        final Map<Long, Double> position;
        final Map<Long, Double> momentum;
        final Map<Long, Double> gradient;

        LeapFrogged(Map<Long, Double> position,
                    Map<Long, Double> momentum,
                    Map<Long, Double> gradient) {
            this.position = position;
            this.momentum = momentum;
            this.gradient = gradient;
        }
    }

    private static class BuiltTree {

        Map<Long, Double> positionBackward;
        Map<Long, Double> gradientBackward;
        Map<Long, Double> momentumBackward;
        Map<Long, Double> positionForward;
        Map<Long, Double> gradientForward;
        Map<Long, Double> momentumForward;
        Map<Long, Double> acceptedPosition;
        Map<Long, Double> gradientAtAcceptedPosition;
        double logOfMasterPAtAcceptedPosition;
        Map<Long, ?> sampleAtAcceptedPosition;
        int acceptedLeapfrogCount;
        boolean shouldContinueFlag;

        BuiltTree(Map<Long, Double> positionBackward,
                  Map<Long, Double> gradientBackward,
                  Map<Long, Double> momentumBackward,
                  Map<Long, Double> positionForward,
                  Map<Long, Double> gradientForward,
                  Map<Long, Double> momentumForward,
                  Map<Long, Double> acceptedPosition,
                  Map<Long, Double> gradientAtAcceptedPosition,
                  double logOfMasterPAtAcceptedPosition,
                  Map<Long, ?> sampleAtAcceptedPosition,
                  int acceptedLeapfrogCount,
                  boolean shouldContinueFlag) {

            this.positionBackward = positionBackward;
            this.gradientBackward = gradientBackward;
            this.momentumBackward = momentumBackward;
            this.positionForward = positionForward;
            this.gradientForward = gradientForward;
            this.momentumForward = momentumForward;
            this.acceptedPosition = acceptedPosition;
            this.gradientAtAcceptedPosition = gradientAtAcceptedPosition;
            this.logOfMasterPAtAcceptedPosition = logOfMasterPAtAcceptedPosition;
            this.sampleAtAcceptedPosition = sampleAtAcceptedPosition;
            this.acceptedLeapfrogCount = acceptedLeapfrogCount;
            this.shouldContinueFlag = shouldContinueFlag;
        }
    }

}

