package io.improbable.keanu.algorithms.mcmc;

import io.improbable.keanu.algorithms.mcmc.proposal.MHStepVariableSelector;
import io.improbable.keanu.algorithms.mcmc.proposal.ProposalDistribution;
import io.improbable.keanu.network.BayesianNetwork;
import io.improbable.keanu.network.NetworkState;
import io.improbable.keanu.network.SimpleNetworkState;
import io.improbable.keanu.vertices.Vertex;
import io.improbable.keanu.vertices.dbl.KeanuRandom;
import lombok.Builder;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.improbable.keanu.algorithms.mcmc.proposal.MHStepVariableSelector.SINGLE_VARIABLE_SELECTOR;

/**
 * Simulated Annealing is a modified version of Metropolis Hastings that causes the MCMC random walk to
 * tend towards the Maximum A Posteriori (MAP)
 */
@Builder
public class SimulatedAnnealing {

    public static SimulatedAnnealing withDefaultConfig() {
        return withDefaultConfig(KeanuRandom.getDefaultRandom());
    }

    public static SimulatedAnnealing withDefaultConfig(KeanuRandom random) {
        return SimulatedAnnealing.builder()
            .proposalDistribution(ProposalDistribution.usePrior())
            .variableSelector(SINGLE_VARIABLE_SELECTOR)
            .useCacheOnRejection(true)
            .random(random)
            .build();
    }

    private final KeanuRandom random;
    private final ProposalDistribution proposalDistribution;

    @Builder.Default
    private final MHStepVariableSelector variableSelector = SINGLE_VARIABLE_SELECTOR;

    @Builder.Default
    private final boolean useCacheOnRejection = true;

    public NetworkState getMaxAPosteriori(BayesianNetwork bayesNet,
                                          int sampleCount) {
        AnnealingSchedule schedule = exponentialSchedule(sampleCount, 2, 0.01);
        return getMaxAPosteriori(bayesNet, sampleCount, schedule);
    }

    /**
     * Finds the MAP using the default annealing schedule, which is an exponential decay schedule.
     *
     * @param bayesNet          a bayesian network containing latent vertices
     * @param sampleCount       the number of samples to take
     * @param annealingSchedule the schedule to update T (temperature) as a function of sample number.
     * @return the NetworkState that represents the Max A Posteriori
     */
    public NetworkState getMaxAPosteriori(BayesianNetwork bayesNet,
                                          int sampleCount,
                                          AnnealingSchedule annealingSchedule) {

        bayesNet.cascadeObservations();

        if (bayesNet.isInImpossibleState()) {
            throw new IllegalArgumentException("Cannot start optimizer on zero probability network");
        }

        Map<Long, ?> maxSamplesByVertex = new HashMap<>();
        List<Vertex> latentVertices = bayesNet.getLatentVertices();

        double logProbabilityBeforeStep = bayesNet.getLogOfMasterP();
        double maxLogP = logProbabilityBeforeStep;
        setSamplesAsMax(maxSamplesByVertex, latentVertices);

        MetropolisHastingsStep mhStep = new MetropolisHastingsStep(
            latentVertices,
            proposalDistribution,
            true,
            random
        );

        for (int sampleNum = 0; sampleNum < sampleCount; sampleNum++) {

            Vertex<?> chosenVertex = latentVertices.get(sampleNum % latentVertices.size());

            double temperature = annealingSchedule.getTemperature(sampleNum);
            logProbabilityBeforeStep = mhStep.step(
                Collections.singleton(chosenVertex),
                logProbabilityBeforeStep,
                temperature
            ).getLogProbabilityAfterStep();

            if (logProbabilityBeforeStep > maxLogP) {
                maxLogP = logProbabilityBeforeStep;
                setSamplesAsMax(maxSamplesByVertex, latentVertices);
            }
        }

        return new SimpleNetworkState(maxSamplesByVertex);
    }

    private static void setSamplesAsMax(Map<Long, ?> samples, List<? extends Vertex> fromVertices) {
        fromVertices.forEach(vertex -> setSampleForVertex((Vertex<?>) vertex, samples));
    }

    private static <T> void setSampleForVertex(Vertex<T> vertex, Map<Long, ?> samples) {
        ((Map<Long, ? super T>) samples).put(vertex.getId(), vertex.getValue());
    }

    /**
     * An annealing schedule determines how T (temperature) changes as
     * a function of the current iteration number (i.e. sample number)
     */
    public interface AnnealingSchedule {
        double getTemperature(int iteration);
    }

    /**
     * @param iterations the number of iterations annealing over
     * @param startT     the value of T at iteration 0
     * @param endT       the value of T at the last iteration
     * @return the annealing schedule
     */
    public static AnnealingSchedule exponentialSchedule(int iterations, double startT, double endT) {

        final double minusK = Math.log(endT / startT) / iterations;

        return n -> startT * Math.exp(minusK * n);
    }

}
