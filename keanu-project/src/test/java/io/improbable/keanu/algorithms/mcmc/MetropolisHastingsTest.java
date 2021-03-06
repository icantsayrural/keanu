package io.improbable.keanu.algorithms.mcmc;

import io.improbable.keanu.algorithms.NetworkSamples;
import io.improbable.keanu.network.BayesianNetwork;
import io.improbable.keanu.tensor.dbl.DoubleTensor;
import io.improbable.keanu.vertices.bool.BoolVertex;
import io.improbable.keanu.vertices.bool.probabilistic.Flip;
import io.improbable.keanu.vertices.dbl.DoubleVertex;
import io.improbable.keanu.vertices.dbl.KeanuRandom;
import io.improbable.keanu.vertices.dbl.nonprobabilistic.operators.unary.DoubleUnaryOpLambda;
import io.improbable.keanu.vertices.dbl.probabilistic.GaussianVertex;
import io.improbable.keanu.vertices.generic.nonprobabilistic.If;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

public class MetropolisHastingsTest {

    private KeanuRandom random;

    @Before
    public void setup() {
        random = new KeanuRandom(1);
    }

    @Test
    public void samplesContinuousPrior() {

        DoubleVertex A = new GaussianVertex(20.0, 1.0);
        DoubleVertex B = new GaussianVertex(20.0, 1.0);

        A.setValue(20.0);
        B.setValue(20.0);

        DoubleVertex Cobserved = new GaussianVertex(A.plus(B), 1.0);

        Cobserved.observe(46.0);

        BayesianNetwork bayesNet = new BayesianNetwork(Arrays.asList(A, B, Cobserved));
        bayesNet.probeForNonZeroProbability(100, random);

        NetworkSamples posteriorSamples = MetropolisHastings.withDefaultConfig().getPosteriorSamples(
            bayesNet,
            Arrays.asList(A, B),
            100000
        );

        double averagePosteriorA = posteriorSamples.getDoubleTensorSamples(A).getAverages().scalar();
        double averagePosteriorB = posteriorSamples.getDoubleTensorSamples(B).getAverages().scalar();

        double actual = averagePosteriorA + averagePosteriorB;
        assertEquals(44.0, actual, 0.1);
    }

    @Test
    public void samplesContinuousTensorPrior() {

        int[] shape = new int[]{1, 1};
        DoubleVertex A = new GaussianVertex(shape, 20.0, 1.0);
        DoubleVertex B = new GaussianVertex(shape, 20.0, 1.0);

        A.setValue(20.0);
        B.setValue(20.0);

        DoubleVertex Cobserved = new GaussianVertex(A.plus(B), 1.0);
        Cobserved.observe(46.0);

        BayesianNetwork bayesNet = new BayesianNetwork(Arrays.asList(A, B, Cobserved));
        bayesNet.probeForNonZeroProbability(100, random);

        NetworkSamples posteriorSamples = MetropolisHastings.withDefaultConfig().getPosteriorSamples(
            bayesNet,
            Arrays.asList(A, B),
            100000
        );

        DoubleTensor averagePosteriorA = posteriorSamples.getDoubleTensorSamples(A).getAverages();
        DoubleTensor averagePosteriorB = posteriorSamples.getDoubleTensorSamples(B).getAverages();

        DoubleTensor allActuals = averagePosteriorA.plus(averagePosteriorB);

        for (double actual : allActuals.asFlatDoubleArray()) {
            assertEquals(44.0, actual, 0.1);
        }
    }

    @Test
    public void samplesSimpleDiscretePrior() {

        Flip A = new Flip(0.5);

        DoubleVertex B = If.isTrue(A)
            .then(0.9)
            .orElse(0.1);

        Flip C = new Flip(B);

        C.observe(true);

        BayesianNetwork bayesNet = new BayesianNetwork(Arrays.asList(A, B, C));
        bayesNet.probeForNonZeroProbability(100, random);

        NetworkSamples posteriorSamples = MetropolisHastings.withDefaultConfig().getPosteriorSamples(
            bayesNet,
            Collections.singletonList(A),
            10000
        );

        double postProbTrue = posteriorSamples.get(A).probability(v -> v.scalar());

        assertEquals(0.9, postProbTrue, 0.01);
    }

    @Test
    public void samplesComplexDiscretePrior() {

        Flip A = new Flip(0.5);
        Flip B = new Flip(0.5);

        BoolVertex C = A.or(B);

        DoubleVertex D = If.isTrue(C)
            .then(0.9)
            .orElse(0.1);

        Flip E = new Flip(D);

        E.observe(true);

        BayesianNetwork bayesNet = new BayesianNetwork(Arrays.asList(A, B, C, D, E));
        bayesNet.probeForNonZeroProbability(100, random);

        NetworkSamples posteriorSamples = MetropolisHastings.withDefaultConfig().getPosteriorSamples(
            bayesNet,
            Collections.singletonList(A),
            100000
        );

        double postProbTrue = posteriorSamples.get(A).probability(v -> v.scalar());

        assertEquals(0.643, postProbTrue, 0.01);
    }

    @Test
    public void samplesFromPriorWithObservedDeterministic() {

        Flip A = new Flip(0.5);
        Flip B = new Flip(0.5);
        BoolVertex C = A.or(B);
        C.observe(false);

        BayesianNetwork net = new BayesianNetwork(A.getConnectedGraph());
        net.probeForNonZeroProbability(100, random);

        NetworkSamples posteriorSamples = MetropolisHastings.withDefaultConfig().getPosteriorSamples(
            net,
            Collections.singletonList(A),
            10000
        );

        double postProbTrue = posteriorSamples.get(A).probability(v -> v.scalar());

        assertEquals(0.0, postProbTrue, 0.01);
    }

    @Test
    public void doesNotDoExtraWorkOnRejectionWhenRejectionCacheEnabled() {
        AtomicInteger n = new AtomicInteger(0);

        DoubleVertex start = new GaussianVertex(new int[]{1, 3}, 0, 1);

        DoubleVertex blackBox = new DoubleUnaryOpLambda<>(start,
            (startValue) -> {
                n.incrementAndGet();
                return startValue.plus(1);
            }
        );

        DoubleVertex pluck0 = new DoubleUnaryOpLambda<>(blackBox,
            bb -> DoubleTensor.scalar(bb.getValue(0))
        );

        DoubleVertex pluck1 = new DoubleUnaryOpLambda<>(blackBox,
            bb -> DoubleTensor.scalar(bb.getValue(1))
        );

        DoubleVertex pluck2 = new DoubleUnaryOpLambda<>(blackBox,
            bb -> DoubleTensor.scalar(bb.getValue(2))
        );

        GaussianVertex out1 = new GaussianVertex(pluck0, 1);
        GaussianVertex out2 = new GaussianVertex(pluck1, 1);
        GaussianVertex out3 = new GaussianVertex(pluck2, 1);

        out1.observe(0);
        out2.observe(0);
        out3.observe(0);

        int sampleCount = 100;
        BayesianNetwork network = new BayesianNetwork(start.getConnectedGraph());

        MetropolisHastings.withDefaultConfig().getPosteriorSamples(
            network,
            network.getLatentVertices(),
            sampleCount
        );

        assertEquals(sampleCount + 1, n.get());
    }

}
