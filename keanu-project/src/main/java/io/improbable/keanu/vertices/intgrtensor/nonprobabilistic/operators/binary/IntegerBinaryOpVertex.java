package io.improbable.keanu.vertices.intgrtensor.nonprobabilistic.operators.binary;


import io.improbable.keanu.tensor.intgr.IntegerTensor;
import io.improbable.keanu.vertices.dbltensor.KeanuRandom;
import io.improbable.keanu.vertices.intgrtensor.IntegerVertex;
import io.improbable.keanu.vertices.intgrtensor.nonprobabilistic.NonProbabilisticInteger;

public abstract class IntegerBinaryOpVertex extends NonProbabilisticInteger {

    protected final IntegerVertex a;
    protected final IntegerVertex b;

    public IntegerBinaryOpVertex(int[] shape, IntegerVertex a, IntegerVertex b) {
        this.a = a;
        this.b = b;
        setParents(a, b);
        setValue(IntegerTensor.placeHolder(shape));
    }

    @Override
    public IntegerTensor sample(KeanuRandom random) {
        return op(a.sample(random), b.sample(random));
    }

    public IntegerTensor getDerivedValue() {
        return op(a.getValue(), b.getValue());
    }

    protected abstract IntegerTensor op(IntegerTensor a, IntegerTensor b);
}