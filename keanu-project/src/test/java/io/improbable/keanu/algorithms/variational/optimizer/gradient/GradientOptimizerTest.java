package io.improbable.keanu.algorithms.variational.optimizer.gradient;

import io.improbable.keanu.vertices.dbl.probabilistic.GaussianVertex;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertTrue;

public class GradientOptimizerTest {

    @Test
    public void doesCallOnFitnessAndOnGradientHandler() {
        AtomicInteger fitnessTimesCalled = new AtomicInteger(0);
        AtomicInteger gradientTimesCalled = new AtomicInteger(0);
        GradientOptimizer optimizer = GradientOptimizer.ofConnectedGraph(
            new GaussianVertex(0, 1)
        );
        optimizer.onFitnessCalculation((point, fitness) -> fitnessTimesCalled.incrementAndGet());
        optimizer.onGradientCalculation((point, fitness) -> gradientTimesCalled.incrementAndGet());
        optimizer.maxAPosteriori();

        assertTrue(fitnessTimesCalled.get() > 0);
        assertTrue(gradientTimesCalled.get() > 0);
    }
}
