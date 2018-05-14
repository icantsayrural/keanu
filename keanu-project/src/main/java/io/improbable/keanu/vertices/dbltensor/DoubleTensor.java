package io.improbable.keanu.vertices.dbltensor;

import java.util.HashMap;
import java.util.Map;

public interface DoubleTensor extends Tensor {

    static DoubleTensor scalar(double scalarValue) {
        return new SimpleScalarTensor(scalarValue);
    }

    static Map<String, DoubleTensor> fromScalars(Map<String, Double> scalars) {
        Map<String, DoubleTensor> asTensors = new HashMap<>();

        for (Map.Entry<String, Double> entry : scalars.entrySet()) {
            asTensors.put(entry.getKey(), DoubleTensor.scalar(entry.getValue()));
        }

        return asTensors;
    }

    double scalar();

}