package io.improbable.keanu.vertices.dbl.nonprobabilistic.diff;

import io.improbable.keanu.tensor.TensorShape;
import io.improbable.keanu.tensor.bool.BooleanTensor;
import io.improbable.keanu.tensor.dbl.DoubleTensor;

import java.util.*;

public class DualNumber {

    private DoubleTensor value;
    private PartialDerivatives partialDerivatives;

    public DualNumber(DoubleTensor value, PartialDerivatives partialDerivatives) {
        this.value = value;
        this.partialDerivatives = partialDerivatives;
    }

    public DualNumber(DoubleTensor value, Map<Long, DoubleTensor> partialDerivatives) {
        this(value, new PartialDerivatives(partialDerivatives));
    }

    public DualNumber(DoubleTensor value, long infinitesimalLabel) {
        this(value, new PartialDerivatives(Collections.singletonMap(infinitesimalLabel, DoubleTensor.ones(value.getShape()))));
    }

    public static DualNumber createConstant(DoubleTensor value) {
        return new DualNumber(value, PartialDerivatives.OF_CONSTANT);
    }

    public static DualNumber createWithRespectToSelf(long withRespectTo, DoubleTensor value) {
        return new DualNumber(value, PartialDerivatives.withRespectToSelf(withRespectTo, value.getShape()));
    }

    public DoubleTensor getValue() {
        return value;
    }

    public PartialDerivatives getPartialDerivatives() {
        return partialDerivatives;
    }

    public boolean isOfConstant() {
        return partialDerivatives.isEmpty();
    }

    public DualNumber add(DualNumber that) {
        // dc = da + db;
        DoubleTensor newValue = this.value.plus(that.value);
        PartialDerivatives newInf = this.partialDerivatives.add(that.partialDerivatives);
        return new DualNumber(newValue, newInf);
    }

    public DualNumber subtract(DualNumber that) {
        // dc = da - db;
        DoubleTensor newValue = this.value.minus(that.value);
        PartialDerivatives newInf = this.partialDerivatives.subtract(that.partialDerivatives);
        return new DualNumber(newValue, newInf);
    }

    public DualNumber matrixMultiplyBy(DualNumber that) {
        // dc = A * db + da * B;
        DoubleTensor newValue = this.value.matrixMultiply(that.value);
        PartialDerivatives thisInfMultiplied;
        PartialDerivatives thatInfMultiplied;

        if (this.partialDerivatives.isEmpty()) {
            thisInfMultiplied = PartialDerivatives.OF_CONSTANT;
        } else {
            thisInfMultiplied = PartialDerivatives.matrixMultiply(this.partialDerivatives, that.value, true);
        }

        if (that.partialDerivatives.isEmpty()) {
            thatInfMultiplied = PartialDerivatives.OF_CONSTANT;
        } else {
            thatInfMultiplied = PartialDerivatives.matrixMultiply(that.partialDerivatives, this.value, false);
        }

        PartialDerivatives newInf = thisInfMultiplied.add(thatInfMultiplied);
        return new DualNumber(newValue, newInf);
    }

    public DualNumber multiplyBy(DualNumber that) {
        // dc = A * db + da * B;
        DoubleTensor newValue = this.value.times(that.value);
        PartialDerivatives thisInfMultiplied;
        PartialDerivatives thatInfMultiplied;

        if (this.partialDerivatives.isEmpty()) {
            thisInfMultiplied = PartialDerivatives.OF_CONSTANT;
        } else {
            thisInfMultiplied = this.partialDerivatives.multiplyBy(that.value);
        }

        if (that.partialDerivatives.isEmpty()) {
            thatInfMultiplied = PartialDerivatives.OF_CONSTANT;
        } else {
            thatInfMultiplied = that.partialDerivatives.multiplyBy(this.value);
        }

        PartialDerivatives newInf = thisInfMultiplied.add(thatInfMultiplied);
        return new DualNumber(newValue, newInf);
    }

    public DualNumber divideBy(DualNumber that) {
        // dc = (B * da - A * db) / B^2;
        DoubleTensor newValue = this.value.div(that.value);
        PartialDerivatives thisInfMultiplied;
        PartialDerivatives thatInfMultiplied;
        PartialDerivatives newInf;

        if (this.partialDerivatives.isEmpty()) {
            thisInfMultiplied = PartialDerivatives.OF_CONSTANT;
        } else {
            thisInfMultiplied = this.partialDerivatives.multiplyBy(that.value);
        }

        if (that.partialDerivatives.isEmpty()) {
            thatInfMultiplied = PartialDerivatives.OF_CONSTANT;
        } else {
            thatInfMultiplied = that.partialDerivatives.multiplyBy(this.value);
        }

        if (thisInfMultiplied.isEmpty() && thatInfMultiplied.isEmpty()) {
            newInf = PartialDerivatives.OF_CONSTANT;
        } else {
            newInf = thisInfMultiplied.subtract(thatInfMultiplied).divideBy(that.value.times(that.value));
        }

        return new DualNumber(newValue, newInf);
    }

    public DualNumber pow(DualNumber that) {
        // dc = (A ^ B) * B * (dA / A) + (dB * log (A))
        DoubleTensor newValue = this.value.pow(that.value);
        PartialDerivatives thisInfBase;
        PartialDerivatives thisInfExponent;

        if (this.partialDerivatives.isEmpty()) {
            thisInfBase = PartialDerivatives.OF_CONSTANT;
        } else {
            thisInfBase = this.partialDerivatives.multiplyBy(that.value.times(this.value.pow(that.value.minus(1))));
        }

        if (that.partialDerivatives.isEmpty()) {
            thisInfExponent = PartialDerivatives.OF_CONSTANT;
        } else {
            thisInfExponent = that.partialDerivatives.multiplyBy(this.value.log().timesInPlace(newValue));
        }

        PartialDerivatives newInf = thisInfBase.add(thisInfExponent);
        return new DualNumber(newValue, newInf);
    }

    public DualNumber plus(DualNumber that) {
        return add(that);
    }

    public DualNumber minus(DualNumber that) {
        return subtract(that);
    }

    public DualNumber times(DualNumber that) {
        return multiplyBy(that);
    }

    public DualNumber div(DualNumber that) {
        return divideBy(that);
    }

    public DualNumber plus(double value) {
        DoubleTensor newValue = this.value.plus(value);
        PartialDerivatives clonedInf = this.partialDerivatives.clone();
        return new DualNumber(newValue, clonedInf);
    }

    public DualNumber minus(double value) {
        DoubleTensor newValue = this.value.minus(value);
        PartialDerivatives clonedInf = this.partialDerivatives.clone();
        return new DualNumber(newValue, clonedInf);
    }

    public DualNumber times(double value) {
        DoubleTensor newValue = this.value.times(value);
        PartialDerivatives newInf = this.partialDerivatives.multiplyBy(value);
        return new DualNumber(newValue, newInf);
    }

    public DualNumber div(double value) {
        DoubleTensor newValue = this.value.div(value);
        PartialDerivatives newPartial = this.partialDerivatives.divideBy(value);
        return new DualNumber(newValue, newPartial);
    }

    public DualNumber unaryMinus() {
        return times(-1.0);
    }

    public DualNumber exp() {
        DoubleTensor newValue = value.exp();
        if (this.partialDerivatives.isEmpty()) {
            return new DualNumber(newValue, PartialDerivatives.OF_CONSTANT);
        } else {
            return new DualNumber(newValue, this.partialDerivatives.multiplyBy(newValue));
        }
    }

    public DualNumber sin() {
        DoubleTensor newValue = value.sin();
        if (this.partialDerivatives.isEmpty()) {
            return new DualNumber(newValue, PartialDerivatives.OF_CONSTANT);
        } else {
            DoubleTensor dSin = value.cos();
            return new DualNumber(newValue, this.partialDerivatives.multiplyBy(dSin));
        }
    }

    public DualNumber cos() {
        DoubleTensor newValue = value.cos();
        if (this.partialDerivatives.isEmpty()) {
            return new DualNumber(newValue, PartialDerivatives.OF_CONSTANT);
        } else {
            DoubleTensor dCos = value.sin().unaryMinusInPlace();
            return new DualNumber(newValue, this.partialDerivatives.multiplyBy(dCos));
        }
    }

    public DualNumber tan() {
        DoubleTensor newValue = value.tan();
        if (this.partialDerivatives.isEmpty()) {
            return new DualNumber(newValue, PartialDerivatives.OF_CONSTANT);
        } else {
            DoubleTensor dTan = value.cos().powInPlace(2).reciprocalInPlace();
            return new DualNumber(newValue, this.partialDerivatives.multiplyBy(dTan));
        }
    }

    public DualNumber asin() {
        DoubleTensor newValue = value.asin();
        if (this.partialDerivatives.isEmpty()) {
            return new DualNumber(newValue, PartialDerivatives.OF_CONSTANT);
        } else {
            DoubleTensor dArcSin = (value.unaryMinus().timesInPlace(value).plusInPlace(1))
                .sqrtInPlace().reciprocalInPlace();
            return new DualNumber(newValue, this.partialDerivatives.multiplyBy(dArcSin));
        }
    }

    public DualNumber acos() {
        DoubleTensor newValue = value.acos();
        if (this.partialDerivatives.isEmpty()) {
            return new DualNumber(newValue, PartialDerivatives.OF_CONSTANT);
        } else {
            DoubleTensor dArcCos = value.unaryMinus().timesInPlace(value).plusInPlace(1)
                .sqrtInPlace().reciprocalInPlace().unaryMinusInPlace();
            return new DualNumber(newValue, this.partialDerivatives.multiplyBy(dArcCos));
        }
    }

    public DualNumber atan() {
        DoubleTensor newValue = value.atan();
        if (this.partialDerivatives.isEmpty()) {
            return new DualNumber(newValue, PartialDerivatives.OF_CONSTANT);
        } else {
            DoubleTensor dArcTan = value.powInPlace(2).plusInPlace(1).reciprocalInPlace();
            return new DualNumber(newValue, this.partialDerivatives.multiplyBy(dArcTan));
        }
    }

    public DualNumber log() {
        DoubleTensor newValue = value.log();
        if (this.partialDerivatives.isEmpty()) {
            return new DualNumber(newValue, PartialDerivatives.OF_CONSTANT);
        } else {
            return new DualNumber(newValue, this.partialDerivatives.divideBy(value));
        }
    }

    public DualNumber sum() {
        DoubleTensor sumOfAll = DoubleTensor.scalar(value.sum());
        int[] resultDims = TensorShape.dimensionRange(0, value.getRank());
        return new DualNumber(sumOfAll, this.partialDerivatives.sum(false, resultDims));
    }

    public DualNumber reshape(int[] proposedShape) {
        PartialDerivatives reshapedPartialDerivatives = this.partialDerivatives.reshape(getValue().getRank(), proposedShape);
        return new DualNumber(value.reshape(proposedShape), reshapedPartialDerivatives);
    }

    public DualNumber slice(int dimension, int index) {
        PartialDerivatives slicedPartialDerivatives = this.partialDerivatives.slice(dimension, index);
        return new DualNumber(value.slice(dimension, index), slicedPartialDerivatives);
    }

    public DualNumber concat(int dimension, List<DualNumber> dualToConcat, DoubleTensor... toConcat) {
        Map<Long, DoubleTensor> concatenatedPartialDerivates = new HashMap<>();
        Map<Long, List<DoubleTensor>> combinedPartialDerivativesOfInputs = new HashMap<>();

        for (Map.Entry<Long, DoubleTensor> partial : this.partialDerivatives.asMap().entrySet()) {
            combinedPartialDerivativesOfInputs.computeIfAbsent(partial.getKey(), k -> new ArrayList<>()).add(partial.getValue());
        }

        for (int i = 0; i < dualToConcat.size(); i++) {
            for (Map.Entry<Long, DoubleTensor> partial : dualToConcat.get(i).getPartialDerivatives().asMap().entrySet()) {
                combinedPartialDerivativesOfInputs.computeIfAbsent(partial.getKey(), k -> new ArrayList<>()).add(partial.getValue());
            }
        }

        for (Map.Entry<Long, List<DoubleTensor>> partials : combinedPartialDerivativesOfInputs.entrySet()) {
            concatenatedPartialDerivates.put(partials.getKey(), concatPartialDerivates(dimension, partials.getValue()));
        }

        DoubleTensor concatValue = this.getValue().concat(dimension, toConcat);
        return new DualNumber(concatValue, concatenatedPartialDerivates);

    }

    private DoubleTensor concatPartialDerivates(int dimension, List<DoubleTensor> partialDerivates) {
        if (partialDerivates.size() == 1) {
            return partialDerivates.get(0);
        } else {
            DoubleTensor primaryTensor = partialDerivates.remove(0);
            DoubleTensor[] derivativesToConcat = new DoubleTensor[partialDerivates.size()];
            return primaryTensor.concat(dimension, partialDerivates.toArray(derivativesToConcat));
        }
    }

    public DualNumber pluck(int[] inputShape, int... index) {
        Map<Long, DoubleTensor> pluckedDuals = new HashMap<>();
        long inputLength = TensorShape.getLength(inputShape);
        int flatIndex = TensorShape.getFlatIndex(inputShape, TensorShape.getRowFirstStride(inputShape), index);

        for (Map.Entry<Long, DoubleTensor> entry : this.partialDerivatives.asMap().entrySet()) {
            DoubleTensor plucked = pluckedFromPartial(entry.getValue(), inputLength, flatIndex);
            pluckedDuals.put(entry.getKey(), plucked);
        }

        return new DualNumber(DoubleTensor.scalar(this.value.getValue(index)), pluckedDuals);
    }

    private DoubleTensor pluckedFromPartial(DoubleTensor partial, long inputLength, int inputFlatIndex) {
        int[] partialShape = partial.getShape();
        long partialLength = TensorShape.getLength(partialShape);
        long howManyValuesToPluckFromPartial = partialLength / inputLength;
        int flatIndexOfPartial = (int) howManyValuesToPluckFromPartial * inputFlatIndex;
        double[] flatPartial = partial.asFlatDoubleArray();
        double[] pluckedValues = new double[(int) howManyValuesToPluckFromPartial];

        for (int i = flatIndexOfPartial; i < flatIndexOfPartial + howManyValuesToPluckFromPartial; i++) {
            pluckedValues[i - flatIndexOfPartial] = flatPartial[i];
        }

        int[] newShape = Arrays.copyOfRange(partialShape, partialShape.length - 2, partialShape.length);
        newShape = TensorShape.shapeToDesiredRankByPrependingOnes(newShape, partial.getShape().length);
        return DoubleTensor.create(pluckedValues, newShape);
    }

    public DualNumber ifThenElse(BooleanTensor predicate, DualNumber els) {
        if (predicate.allTrue()) {
            return new DualNumber(this.value, this.getPartialDerivatives());
        } else if (predicate.allFalse()) {
            return new DualNumber(els.value, els.getPartialDerivatives());
        } else {
            return pluckFromMixedPredicate(predicate, this, els);
        }
    }

    private DualNumber pluckFromMixedPredicate(BooleanTensor predicate, DualNumber thn, DualNumber els) {
        int[] thenShape = thn.value.getShape();
        double[] flatPredicate = predicate.asFlatDoubleArray();
        Map<Long, List<DoubleTensor>> ifAndElseDualNumbers = new HashMap<>();

        for (int i = 0; i < flatPredicate.length; i++) {
            boolean condition = flatPredicate[i] == 1.0;
            if (condition) {
                pluckFromThenAndElse(thn, els, ifAndElseDualNumbers, thenShape, i);
            } else {
                pluckFromThenAndElse(els, thn, ifAndElseDualNumbers, thenShape, i);
            }
        }

        Map<Long, DoubleTensor> newPartials = concatenatePluckedPartials(ifAndElseDualNumbers, thn, els);
        return new DualNumber(predicate.setDoubleIf(thn.value, els.value), newPartials);
    }

    private void pluckFromThenAndElse(DualNumber primary, DualNumber secondary, Map<Long, List<DoubleTensor>> partials, int[] shape, int index) {
        int[] currentIndex = TensorShape.getShapeIndices(shape, TensorShape.getRowFirstStride(shape), index);
        DualNumber primaryDualNumber = primary.pluck(shape, currentIndex);
        DualNumber secondaryDualNumber = secondary.pluck(shape, currentIndex);
        Map<Long, DoubleTensor> primaryDualsMap = primaryDualNumber.getPartialDerivatives().asMap();
        Map<Long, DoubleTensor> secondaryWithPrimaryRemoved = removePrimaryFromSecondaryAndZero(primaryDualNumber, secondaryDualNumber);
        addToMap(partials, primaryDualsMap, secondaryWithPrimaryRemoved);
    }

    private Map<Long, DoubleTensor> concatenatePluckedPartials(Map<Long, List<DoubleTensor>> ifAndElseDualNumbers, DualNumber thn, DualNumber els) {
        Map<Long, DoubleTensor> newPartials = new HashMap<>();

        for (Map.Entry<Long, List<DoubleTensor>> entry : ifAndElseDualNumbers.entrySet()) {
            List<DoubleTensor> value = entry.getValue();
            DoubleTensor primaryTensor = value.remove(0);
            DoubleTensor[] partialsToConcat = new DoubleTensor[value.size()];
            DoubleTensor concatenatedPartials = primaryTensor.concat(0, value.toArray(partialsToConcat));

            int[] originalPartialShape = thn.getPartialDerivatives().asMap().containsKey(entry.getKey()) ?
                thn.getPartialDerivatives().withRespectTo(entry.getKey()).getShape() :
                els.getPartialDerivatives().withRespectTo(entry.getKey()).getShape();

            newPartials.put(entry.getKey(), concatenatedPartials.reshape(originalPartialShape));
        }

        return newPartials;
    }

    private Map<Long, List<DoubleTensor>> addToMap(Map<Long, List<DoubleTensor>> toBeConcatted, Map<Long, DoubleTensor> a, Map<Long, DoubleTensor> b) {
        for (Map.Entry<Long, DoubleTensor> entry : a.entrySet()) {
            toBeConcatted.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(entry.getValue());
        }
        for (Map.Entry<Long, DoubleTensor> entry : b.entrySet()) {
            toBeConcatted.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(entry.getValue());
        }
        return toBeConcatted;
    }

    private Map<Long, DoubleTensor> removePrimaryFromSecondaryAndZero(DualNumber primary, DualNumber secondary) {
        Map<Long, DoubleTensor> primaryMap = primary.getPartialDerivatives().asMap();
        Map<Long, DoubleTensor> secondaryWithPrimaryRemoved = new HashMap<>();

        for (Map.Entry<Long, DoubleTensor> entry : secondary.getPartialDerivatives().asMap().entrySet()) {
            if (!primaryMap.containsKey(entry.getKey())) {
                DoubleTensor toZero = secondary.getPartialDerivatives().asMap().get(entry.getKey());
                DoubleTensor zeroes = DoubleTensor.zeros(toZero.getShape());
                secondaryWithPrimaryRemoved.put(entry.getKey(), zeroes);
            }
        }

        return secondaryWithPrimaryRemoved;
    }

}
