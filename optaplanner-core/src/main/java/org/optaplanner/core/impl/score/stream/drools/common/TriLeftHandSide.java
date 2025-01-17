/*
 * Copyright 2021 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.optaplanner.core.impl.score.stream.drools.common;

import static java.util.Collections.singletonList;
import static org.drools.model.DSL.exists;
import static org.drools.model.DSL.not;
import static org.drools.model.PatternDSL.betaIndexedBy;
import static org.drools.model.PatternDSL.pattern;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;

import org.drools.model.BetaIndex3;
import org.drools.model.DSL;
import org.drools.model.PatternDSL;
import org.drools.model.Variable;
import org.drools.model.functions.Function3;
import org.drools.model.functions.Predicate4;
import org.drools.model.functions.accumulate.AccumulateFunction;
import org.drools.model.view.ViewItem;
import org.optaplanner.core.api.function.QuadPredicate;
import org.optaplanner.core.api.function.ToIntTriFunction;
import org.optaplanner.core.api.function.ToLongTriFunction;
import org.optaplanner.core.api.function.TriFunction;
import org.optaplanner.core.api.function.TriPredicate;
import org.optaplanner.core.api.score.stream.quad.QuadJoiner;
import org.optaplanner.core.api.score.stream.tri.TriConstraintCollector;
import org.optaplanner.core.impl.score.stream.common.JoinerType;
import org.optaplanner.core.impl.score.stream.drools.DroolsVariableFactory;
import org.optaplanner.core.impl.score.stream.quad.AbstractQuadJoiner;
import org.optaplanner.core.impl.score.stream.quad.FilteringQuadJoiner;
import org.optaplanner.core.impl.score.stream.quad.NoneQuadJoiner;
import org.optaplanner.core.impl.score.stream.tri.NoneTriJoiner;

/**
 * Represents the left hand side of a Drools rule, the result of which are three variables.
 * For more, see {@link UniLeftHandSide} and {@link BiLeftHandSide}.
 *
 * @param <A> generic type of the first resulting variable
 * @param <B> generic type of the second resulting variable
 * @param <C> generic type of the third resulting variable
 */
public final class TriLeftHandSide<A, B, C> extends AbstractLeftHandSide {

    private final PatternVariable<A, ?, ?> patternVariableA;
    private final PatternVariable<B, ?, ?> patternVariableB;
    private final PatternVariable<C, ?, ?> patternVariableC;
    private final TriRuleContext<A, B, C> ruleContext;

    protected TriLeftHandSide(Variable<A> variableA, Variable<B> variableB, PatternVariable<C, ?, ?> patternVariableC,
            DroolsVariableFactory variableFactory) {
        this(new DetachedPatternVariable<>(variableA), new DetachedPatternVariable<>(variableB), patternVariableC,
                variableFactory);
    }

    protected TriLeftHandSide(PatternVariable<A, ?, ?> patternVariableA, PatternVariable<B, ?, ?> patternVariableB,
            PatternVariable<C, ?, ?> patternVariableC, DroolsVariableFactory variableFactory) {
        super(variableFactory);
        this.patternVariableA = Objects.requireNonNull(patternVariableA);
        this.patternVariableB = Objects.requireNonNull(patternVariableB);
        this.patternVariableC = Objects.requireNonNull(patternVariableC);
        this.ruleContext = buildRuleContext();
    }

    private TriRuleContext<A, B, C> buildRuleContext() {
        ViewItem<?>[] viewItems = Stream.of(patternVariableA, patternVariableB, patternVariableC)
                .flatMap(variable -> variable.build().stream())
                .toArray(size -> new ViewItem<?>[size]);
        return new TriRuleContext<>(patternVariableA.getPrimaryVariable(), patternVariableB.getPrimaryVariable(),
                patternVariableC.getPrimaryVariable(), viewItems);
    }

    public TriLeftHandSide<A, B, C> andFilter(TriPredicate<A, B, C> predicate) {
        return new TriLeftHandSide<>(patternVariableA, patternVariableB, patternVariableC.filter(predicate,
                patternVariableA.getPrimaryVariable(), patternVariableB.getPrimaryVariable()), variableFactory);
    }

    private <D> TriLeftHandSide<A, B, C> applyJoiners(Class<D> otherFactType, AbstractQuadJoiner<A, B, C, D> joiner,
            QuadPredicate<A, B, C, D> predicate, boolean shouldExist) {
        Variable<D> toExist = variableFactory.createVariable(otherFactType, "toExist");
        PatternDSL.PatternDef<D> existencePattern = pattern(toExist);
        if (joiner == null) {
            return applyFilters(existencePattern, predicate, shouldExist);
        }
        JoinerType[] joinerTypes = joiner.getJoinerTypes();
        for (int mappingIndex = 0; mappingIndex < joinerTypes.length; mappingIndex++) {
            JoinerType joinerType = joinerTypes[mappingIndex];
            TriFunction<A, B, C, Object> leftMapping = joiner.getLeftMapping(mappingIndex);
            Function<D, Object> rightMapping = joiner.getRightMapping(mappingIndex);
            Predicate4<D, A, B, C> joinPredicate =
                    (d, a, b, c) -> joinerType.matches(leftMapping.apply(a, b, c), rightMapping.apply(d));
            BetaIndex3<D, A, B, C, ?> index = betaIndexedBy(Object.class, getConstraintType(joinerType), mappingIndex,
                    rightMapping::apply, leftMapping::apply, Object.class);
            existencePattern = existencePattern.expr("Join using joiner #" + mappingIndex + " in " + joiner,
                    patternVariableA.getPrimaryVariable(), patternVariableB.getPrimaryVariable(),
                    patternVariableC.getPrimaryVariable(), joinPredicate, index);
        }
        return applyFilters(existencePattern, predicate, shouldExist);
    }

    private <D> TriLeftHandSide<A, B, C> applyFilters(PatternDSL.PatternDef<D> existencePattern,
            QuadPredicate<A, B, C, D> predicate, boolean shouldExist) {
        PatternDSL.PatternDef<D> possiblyFilteredExistencePattern = predicate == null ? existencePattern
                : existencePattern.expr("Filter using " + predicate, patternVariableA.getPrimaryVariable(),
                        patternVariableB.getPrimaryVariable(), patternVariableC.getPrimaryVariable(),
                        (d, a, b, c) -> predicate.test(a, b, c, d));
        ViewItem<?> existenceExpression = exists(possiblyFilteredExistencePattern);
        if (!shouldExist) {
            existenceExpression = not(possiblyFilteredExistencePattern);
        }
        return new TriLeftHandSide<>(patternVariableA, patternVariableB,
                patternVariableC.addDependentExpression(existenceExpression), variableFactory);
    }

    private <D> TriLeftHandSide<A, B, C> existsOrNot(Class<D> dClass, QuadJoiner<A, B, C, D>[] joiners,
            boolean shouldExist) {
        int indexOfFirstFilter = -1;
        // Prepare the joiner and filter that will be used in the pattern
        AbstractQuadJoiner<A, B, C, D> finalJoiner = null;
        QuadPredicate<A, B, C, D> finalFilter = null;
        for (int i = 0; i < joiners.length; i++) {
            AbstractQuadJoiner<A, B, C, D> joiner = (AbstractQuadJoiner<A, B, C, D>) joiners[i];
            boolean hasAFilter = indexOfFirstFilter >= 0;
            if (joiner instanceof NoneQuadJoiner && joiners.length > 1) {
                throw new IllegalStateException("If present, " + NoneTriJoiner.class + " must be the only joiner, got "
                        + Arrays.toString(joiners) + " instead.");
            } else if (!(joiner instanceof FilteringQuadJoiner)) {
                if (hasAFilter) {
                    throw new IllegalStateException("Indexing joiner (" + joiner + ") must not follow a filtering joiner ("
                            + joiners[indexOfFirstFilter] + ").");
                } else { // Merge this Joiner with the existing Joiners.
                    finalJoiner = finalJoiner == null ? joiner : AbstractQuadJoiner.merge(finalJoiner, joiner);
                }
            } else {
                if (!hasAFilter) { // From now on, we only allow filtering joiners.
                    indexOfFirstFilter = i;
                }
                // Merge all filters into one to avoid paying the penalty for lack of indexing more than once.
                finalFilter = finalFilter == null ? joiner.getFilter() : finalFilter.and(joiner.getFilter());
            }
        }
        return applyJoiners(dClass, finalJoiner, finalFilter, shouldExist);
    }

    public <D> TriLeftHandSide<A, B, C> andExists(Class<D> dClass, QuadJoiner<A, B, C, D>[] joiners) {
        return existsOrNot(dClass, joiners, true);
    }

    public <D> TriLeftHandSide<A, B, C> andNotExists(Class<D> dClass, QuadJoiner<A, B, C, D>[] joiners) {
        return existsOrNot(dClass, joiners, false);
    }

    public <D> QuadLeftHandSide<A, B, C, D> andJoin(UniLeftHandSide<D> right, QuadJoiner<A, B, C, D> joiner) {
        AbstractQuadJoiner<A, B, C, D> castJoiner = (AbstractQuadJoiner<A, B, C, D>) joiner;
        JoinerType[] joinerTypes = castJoiner.getJoinerTypes();
        PatternVariable<D, ?, ?> newRight = right.getPatternVariableA();
        for (int mappingIndex = 0; mappingIndex < joinerTypes.length; mappingIndex++) {
            JoinerType joinerType = joinerTypes[mappingIndex];
            newRight = newRight.filterForJoin(patternVariableA.getPrimaryVariable(), patternVariableB.getPrimaryVariable(),
                    patternVariableC.getPrimaryVariable(), castJoiner, joinerType, mappingIndex);
        }
        return new QuadLeftHandSide<>(patternVariableA, patternVariableB, patternVariableC, newRight, variableFactory);
    }

    public <NewA> UniLeftHandSide<NewA> andGroupBy(TriConstraintCollector<A, B, C, ?, NewA> collector) {
        Variable<NewA> accumulateOutput = variableFactory.createVariable("collected");
        ViewItem<?> innerAccumulatePattern =
                joinViewItemsWithLogicalAnd(patternVariableA, patternVariableB, patternVariableC);
        ViewItem<?> outerAccumulatePattern = buildAccumulate(innerAccumulatePattern,
                createAccumulateFunction(collector, accumulateOutput));
        return new UniLeftHandSide<>(accumulateOutput, singletonList(outerAccumulatePattern), variableFactory);
    }

    public <NewA, NewB> BiLeftHandSide<NewA, NewB> andGroupBy(TriConstraintCollector<A, B, C, ?, NewA> collectorA,
            TriConstraintCollector<A, B, C, ?, NewB> collectorB) {
        Variable<NewA> accumulateOutputA = variableFactory.createVariable("collectedA");
        Variable<NewB> accumulateOutputB = variableFactory.createVariable("collectedB");
        ViewItem<?> innerAccumulatePattern =
                joinViewItemsWithLogicalAnd(patternVariableA, patternVariableB, patternVariableC);
        ViewItem<?> outerAccumulatePattern = buildAccumulate(innerAccumulatePattern,
                createAccumulateFunction(collectorA, accumulateOutputA),
                createAccumulateFunction(collectorB, accumulateOutputB));
        return new BiLeftHandSide<>(accumulateOutputA,
                new DirectPatternVariable<>(accumulateOutputB, outerAccumulatePattern), variableFactory);
    }

    public <NewA, NewB, NewC> TriLeftHandSide<NewA, NewB, NewC> andGroupBy(
            TriConstraintCollector<A, B, C, ?, NewA> collectorA, TriConstraintCollector<A, B, C, ?, NewB> collectorB,
            TriConstraintCollector<A, B, C, ?, NewC> collectorC) {
        Variable<NewA> accumulateOutputA = variableFactory.createVariable("collectedA");
        Variable<NewB> accumulateOutputB = variableFactory.createVariable("collectedB");
        Variable<NewC> accumulateOutputC = variableFactory.createVariable("collectedC");
        ViewItem<?> innerAccumulatePattern =
                joinViewItemsWithLogicalAnd(patternVariableA, patternVariableB, patternVariableC);
        ViewItem<?> outerAccumulatePattern = buildAccumulate(innerAccumulatePattern,
                createAccumulateFunction(collectorA, accumulateOutputA),
                createAccumulateFunction(collectorB, accumulateOutputB),
                createAccumulateFunction(collectorC, accumulateOutputC));
        return new TriLeftHandSide<>(accumulateOutputA, accumulateOutputB,
                new DirectPatternVariable<>(accumulateOutputC, outerAccumulatePattern), variableFactory);
    }

    public <NewA, NewB, NewC, NewD> QuadLeftHandSide<NewA, NewB, NewC, NewD> andGroupBy(
            TriConstraintCollector<A, B, C, ?, NewA> collectorA, TriConstraintCollector<A, B, C, ?, NewB> collectorB,
            TriConstraintCollector<A, B, C, ?, NewC> collectorC, TriConstraintCollector<A, B, C, ?, NewD> collectorD) {
        Variable<NewA> accumulateOutputA = variableFactory.createVariable("collectedA");
        Variable<NewB> accumulateOutputB = variableFactory.createVariable("collectedB");
        Variable<NewC> accumulateOutputC = variableFactory.createVariable("collectedC");
        Variable<NewD> accumulateOutputD = variableFactory.createVariable("collectedD");
        ViewItem<?> innerAccumulatePattern =
                joinViewItemsWithLogicalAnd(patternVariableA, patternVariableB, patternVariableC);
        ViewItem<?> outerAccumulatePattern = buildAccumulate(innerAccumulatePattern,
                createAccumulateFunction(collectorA, accumulateOutputA),
                createAccumulateFunction(collectorB, accumulateOutputB),
                createAccumulateFunction(collectorC, accumulateOutputC),
                createAccumulateFunction(collectorD, accumulateOutputD));
        return new QuadLeftHandSide<>(accumulateOutputA, accumulateOutputB, accumulateOutputC,
                new DirectPatternVariable<>(accumulateOutputD, outerAccumulatePattern), variableFactory);
    }

    /**
     * Creates a Drools accumulate function based on a given collector. The accumulate function will take the pattern
     * variables as input and return its result into another {@link Variable}.
     *
     * @param <Out> type of the accumulate result
     * @param collector collector to use in the accumulate function
     * @param out variable in which to store accumulate result
     * @return Drools accumulate function
     */
    private <Out> AccumulateFunction createAccumulateFunction(TriConstraintCollector<A, B, C, ?, Out> collector,
            Variable<Out> out) {
        Variable<A> variableA = patternVariableA.getPrimaryVariable();
        Variable<B> variableB = patternVariableB.getPrimaryVariable();
        Variable<C> variableC = patternVariableC.getPrimaryVariable();
        return new AccumulateFunction(null, () -> new TriAccumulator<>(variableA, variableB, variableC, collector))
                .with(variableA, variableB, variableC)
                .as(out);
    }

    public <NewA> UniLeftHandSide<NewA> andGroupBy(TriFunction<A, B, C, NewA> keyMapping) {
        Variable<NewA> groupKey = variableFactory.createVariable("groupKey");
        ViewItem<?> groupByPattern = buildGroupBy(groupKey, keyMapping::apply);
        return new UniLeftHandSide<>(groupKey, singletonList(groupByPattern), variableFactory);
    }

    public <NewA, NewB> BiLeftHandSide<NewA, NewB> andGroupBy(TriFunction<A, B, C, NewA> keyMappingA,
            TriConstraintCollector<A, B, C, ?, NewB> collectorB) {
        Variable<NewA> groupKey = variableFactory.createVariable("groupKey");
        Variable<NewB> accumulateOutput = variableFactory.createVariable("output");
        ViewItem<?> groupByPattern = buildGroupBy(groupKey, keyMappingA::apply,
                createAccumulateFunction(collectorB, accumulateOutput));
        return new BiLeftHandSide<>(groupKey, new DirectPatternVariable<>(accumulateOutput, groupByPattern),
                variableFactory);
    }

    public <NewA, NewB, NewC> TriLeftHandSide<NewA, NewB, NewC> andGroupBy(TriFunction<A, B, C, NewA> keyMappingA,
            TriConstraintCollector<A, B, C, ?, NewB> collectorB, TriConstraintCollector<A, B, C, ?, NewC> collectorC) {
        Variable<NewA> groupKey = variableFactory.createVariable("groupKey");
        Variable<NewB> accumulateOutputB = variableFactory.createVariable("outputB");
        Variable<NewC> accumulateOutputC = variableFactory.createVariable("outputC");
        ViewItem<?> groupByPattern = buildGroupBy(groupKey, keyMappingA::apply,
                createAccumulateFunction(collectorB, accumulateOutputB),
                createAccumulateFunction(collectorC, accumulateOutputC));
        return new TriLeftHandSide<>(groupKey, accumulateOutputB,
                new DirectPatternVariable<>(accumulateOutputC, groupByPattern), variableFactory);
    }

    public <NewA, NewB, NewC, NewD> QuadLeftHandSide<NewA, NewB, NewC, NewD> andGroupBy(
            TriFunction<A, B, C, NewA> keyMappingA, TriConstraintCollector<A, B, C, ?, NewB> collectorB,
            TriConstraintCollector<A, B, C, ?, NewC> collectorC, TriConstraintCollector<A, B, C, ?, NewD> collectorD) {
        Variable<NewA> groupKey = variableFactory.createVariable("groupKey");
        Variable<NewB> accumulateOutputB = variableFactory.createVariable("outputB");
        Variable<NewC> accumulateOutputC = variableFactory.createVariable("outputC");
        Variable<NewD> accumulateOutputD = variableFactory.createVariable("outputD");
        ViewItem<?> groupByPattern = buildGroupBy(groupKey, keyMappingA::apply,
                createAccumulateFunction(collectorB, accumulateOutputB),
                createAccumulateFunction(collectorC, accumulateOutputC),
                createAccumulateFunction(collectorD, accumulateOutputD));
        return new QuadLeftHandSide<>(groupKey, accumulateOutputB, accumulateOutputC,
                new DirectPatternVariable<>(accumulateOutputD, groupByPattern), variableFactory);
    }

    public <NewA, NewB> BiLeftHandSide<NewA, NewB> andGroupBy(TriFunction<A, B, C, NewA> keyMappingA,
            TriFunction<A, B, C, NewB> keyMappingB) {
        Variable<BiTuple<NewA, NewB>> groupKey = variableFactory.createVariable(BiTuple.class, "groupKey");
        ViewItem<?> groupByPattern = buildGroupBy(groupKey, createCompositeBiGroupKey(keyMappingA, keyMappingB));
        Variable<NewA> newA = variableFactory.createVariable("newA");
        Variable<NewB> newB = variableFactory.createVariable("newB");
        DirectPatternVariable<BiTuple<NewA, NewB>> tuplePatternVar = decompose(groupKey, groupByPattern, newA, newB);
        PatternVariable<NewB, BiTuple<NewA, NewB>, ?> bPatternVar =
                new IndirectPatternVariable<>(tuplePatternVar, newB, tuple -> tuple.b);
        return new BiLeftHandSide<>(newA, bPatternVar, variableFactory);
    }

    /**
     * Takes group key mappings and merges them in such a way that the result is a single composite key.
     * This is necessary because Drools groupBy can only take a single key - therefore multiple variables need to be
     * converted into a singular composite variable.
     *
     * @param keyMappingA mapping for the first variable
     * @param keyMappingB mapping for the second variable
     * @param <NewA> generic type of the first variable
     * @param <NewB> generic type of the second variable
     * @return never null, Drools function to convert the keys to a singular composite key
     */
    private <NewA, NewB> Function3<A, B, C, BiTuple<NewA, NewB>> createCompositeBiGroupKey(
            TriFunction<A, B, C, NewA> keyMappingA, TriFunction<A, B, C, NewB> keyMappingB) {
        return (a, b, c) -> new BiTuple<>(keyMappingA.apply(a, b, c), keyMappingB.apply(a, b, c));
    }

    public <NewA, NewB, NewC> TriLeftHandSide<NewA, NewB, NewC> andGroupBy(TriFunction<A, B, C, NewA> keyMappingA,
            TriFunction<A, B, C, NewB> keyMappingB, TriConstraintCollector<A, B, C, ?, NewC> collectorC) {
        Variable<BiTuple<NewA, NewB>> groupKey = variableFactory.createVariable(BiTuple.class, "groupKey");
        Variable<NewC> accumulateOutput = variableFactory.createVariable("output");
        ViewItem<?> groupByPattern = buildGroupBy(groupKey,
                createCompositeBiGroupKey(keyMappingA, keyMappingB),
                createAccumulateFunction(collectorC, accumulateOutput));
        Variable<NewA> newA = variableFactory.createVariable("newA");
        Variable<NewB> newB = variableFactory.createVariable("newB");
        DirectPatternVariable<BiTuple<NewA, NewB>> tuplePatternVar = decompose(groupKey, groupByPattern, newA, newB);
        return new TriLeftHandSide<>(newA, newB, new DirectPatternVariable<>(accumulateOutput, tuplePatternVar.build()),
                variableFactory);
    }

    public <NewA, NewB, NewC, NewD> QuadLeftHandSide<NewA, NewB, NewC, NewD> andGroupBy(
            TriFunction<A, B, C, NewA> keyMappingA, TriFunction<A, B, C, NewB> keyMappingB,
            TriConstraintCollector<A, B, C, ?, NewC> collectorC, TriConstraintCollector<A, B, C, ?, NewD> collectorD) {
        Variable<BiTuple<NewA, NewB>> groupKey = variableFactory.createVariable(BiTuple.class, "groupKey");
        Variable<NewC> accumulateOutputC = variableFactory.createVariable("outputC");
        Variable<NewD> accumulateOutputD = variableFactory.createVariable("outputD");
        ViewItem<?> groupByPattern = buildGroupBy(groupKey,
                createCompositeBiGroupKey(keyMappingA, keyMappingB),
                createAccumulateFunction(collectorC, accumulateOutputC),
                createAccumulateFunction(collectorD, accumulateOutputD));
        Variable<NewA> newA = variableFactory.createVariable("newA");
        Variable<NewB> newB = variableFactory.createVariable("newB");
        DirectPatternVariable<BiTuple<NewA, NewB>> tuplePatternVar = decompose(groupKey, groupByPattern, newA, newB);
        return new QuadLeftHandSide<>(newA, newB, accumulateOutputC,
                new DirectPatternVariable<>(accumulateOutputD, tuplePatternVar.build()), variableFactory);
    }

    /**
     * Takes group key mappings and merges them in such a way that the result is a single composite key.
     * This is necessary because Drools groupBy can only take a single key - therefore multiple variables need to be
     * converted into a singular composite variable.
     *
     * @param keyMappingA mapping for the first variable
     * @param keyMappingB mapping for the second variable
     * @param keyMappingC mapping for the third variable
     * @param <NewA> generic type of the first variable
     * @param <NewB> generic type of the second variable
     * @param <NewC> generic type of the third variable
     * @return never null, Drools function to convert the keys to a singular composite key
     */
    private <NewA, NewB, NewC> Function3<A, B, C, TriTuple<NewA, NewB, NewC>> createCompositeTriGroupKey(
            TriFunction<A, B, C, NewA> keyMappingA, TriFunction<A, B, C, NewB> keyMappingB,
            TriFunction<A, B, C, NewC> keyMappingC) {
        return (a, b, c) -> new TriTuple<>(keyMappingA.apply(a, b, c), keyMappingB.apply(a, b, c),
                keyMappingC.apply(a, b, c));
    }

    public <NewA, NewB, NewC> TriLeftHandSide<NewA, NewB, NewC> andGroupBy(TriFunction<A, B, C, NewA> keyMappingA,
            TriFunction<A, B, C, NewB> keyMappingB, TriFunction<A, B, C, NewC> keyMappingC) {
        Variable<TriTuple<NewA, NewB, NewC>> groupKey = variableFactory.createVariable(TriTuple.class, "groupKey");
        ViewItem<?> groupByPattern = buildGroupBy(groupKey,
                createCompositeTriGroupKey(keyMappingA, keyMappingB, keyMappingC));
        Variable<NewA> newA = variableFactory.createVariable("newA");
        Variable<NewB> newB = variableFactory.createVariable("newB");
        Variable<NewC> newC = variableFactory.createVariable("newC");
        DirectPatternVariable<TriTuple<NewA, NewB, NewC>> tuplePatternVar =
                decompose(groupKey, groupByPattern, newA, newB, newC);
        PatternVariable<NewC, TriTuple<NewA, NewB, NewC>, ?> cPatternVar =
                new IndirectPatternVariable<>(tuplePatternVar, newC, tuple -> tuple.c);
        return new TriLeftHandSide<>(newA, newB, cPatternVar, variableFactory);
    }

    public <NewA, NewB, NewC, NewD> QuadLeftHandSide<NewA, NewB, NewC, NewD> andGroupBy(
            TriFunction<A, B, C, NewA> keyMappingA, TriFunction<A, B, C, NewB> keyMappingB,
            TriFunction<A, B, C, NewC> keyMappingC, TriConstraintCollector<A, B, C, ?, NewD> collectorD) {
        Variable<TriTuple<NewA, NewB, NewC>> groupKey = variableFactory.createVariable(TriTuple.class, "groupKey");
        Variable<NewD> accumulateOutputD = variableFactory.createVariable("outputD");
        ViewItem<?> groupByPattern = buildGroupBy(groupKey,
                createCompositeTriGroupKey(keyMappingA, keyMappingB, keyMappingC),
                createAccumulateFunction(collectorD, accumulateOutputD));
        Variable<NewA> newA = variableFactory.createVariable("newA");
        Variable<NewB> newB = variableFactory.createVariable("newB");
        Variable<NewC> newC = variableFactory.createVariable("newC");
        DirectPatternVariable<TriTuple<NewA, NewB, NewC>> tuplePatternVar =
                decompose(groupKey, groupByPattern, newA, newB, newC);
        return new QuadLeftHandSide<>(newA, newB, newC,
                new DirectPatternVariable<>(accumulateOutputD, tuplePatternVar.build()), variableFactory);
    }

    /**
     * Takes group key mappings and merges them in such a way that the result is a single composite key.
     * This is necessary because Drools groupBy can only take a single key - therefore multiple variables need to be
     * converted into a singular composite variable.
     *
     * @param keyMappingA mapping for the first variable
     * @param keyMappingB mapping for the second variable
     * @param keyMappingC mapping for the third variable
     * @param <NewA> generic type of the first variable
     * @param <NewB> generic type of the second variable
     * @param <NewC> generic type of the third variable
     * @return never null, Drools function to convert the keys to a singular composite key
     */
    private <NewA, NewB, NewC, NewD> Function3<A, B, C, QuadTuple<NewA, NewB, NewC, NewD>>
            createCompositeQuadGroupKey(TriFunction<A, B, C, NewA> keyMappingA, TriFunction<A, B, C, NewB> keyMappingB,
                    TriFunction<A, B, C, NewC> keyMappingC, TriFunction<A, B, C, NewD> keyMappingD) {
        return (a, b, c) -> new QuadTuple<>(keyMappingA.apply(a, b, c), keyMappingB.apply(a, b, c),
                keyMappingC.apply(a, b, c), keyMappingD.apply(a, b, c));
    }

    public <NewA, NewB, NewC, NewD> QuadLeftHandSide<NewA, NewB, NewC, NewD> andGroupBy(
            TriFunction<A, B, C, NewA> keyMappingA, TriFunction<A, B, C, NewB> keyMappingB,
            TriFunction<A, B, C, NewC> keyMappingC, TriFunction<A, B, C, NewD> keyMappingD) {
        Variable<QuadTuple<NewA, NewB, NewC, NewD>> groupKey = variableFactory.createVariable(QuadTuple.class, "groupKey");
        ViewItem<?> groupByPattern = buildGroupBy(groupKey,
                createCompositeQuadGroupKey(keyMappingA, keyMappingB, keyMappingC, keyMappingD));
        Variable<NewA> newA = variableFactory.createVariable("newA");
        Variable<NewB> newB = variableFactory.createVariable("newB");
        Variable<NewC> newC = variableFactory.createVariable("newC");
        Variable<NewD> newD = variableFactory.createVariable("newD");
        DirectPatternVariable<QuadTuple<NewA, NewB, NewC, NewD>> tuplePatternVar =
                decompose(groupKey, groupByPattern, newA, newB, newC, newD);
        PatternVariable<NewD, QuadTuple<NewA, NewB, NewC, NewD>, ?> dPatternVar =
                new IndirectPatternVariable<>(tuplePatternVar, newD, tuple -> tuple.d);
        return new QuadLeftHandSide<>(newA, newB, newC, dPatternVar, variableFactory);
    }

    public <NewA> UniLeftHandSide<NewA> andMap(TriFunction<A, B, C, NewA> mapping) {
        Variable<NewA> newA = variableFactory.createVariable("mapped", patternVariableA.getPrimaryVariable(),
                patternVariableB.getPrimaryVariable(), patternVariableC.getPrimaryVariable(), mapping);
        List<ViewItem<?>> allPrerequisites = mergeViewItems(patternVariableA, patternVariableB, patternVariableC);
        DirectPatternVariable<NewA> newPatternVariableA = new DirectPatternVariable<>(newA, allPrerequisites);
        return new UniLeftHandSide<>(newPatternVariableA, variableFactory);
    }

    public <NewC> TriLeftHandSide<A, B, NewC> andFlattenLast(Function<C, Iterable<NewC>> mapping) {
        Variable<C> source = patternVariableC.getPrimaryVariable();
        Variable<NewC> newC = variableFactory.createFlattenedVariable("flattened", source, mapping);
        List<ViewItem<?>> allPrerequisites = mergeViewItems(patternVariableA, patternVariableB, patternVariableC);
        PatternVariable<NewC, ?, ?> newPatternVariableC = new DirectPatternVariable<>(newC, allPrerequisites);
        return new TriLeftHandSide<>(patternVariableA.getPrimaryVariable(), patternVariableB.getPrimaryVariable(),
                newPatternVariableC, variableFactory);
    }

    public <Solution_> RuleBuilder<Solution_> andTerminate() {
        return ruleContext.newRuleBuilder();
    }

    public <Solution_> RuleBuilder<Solution_> andTerminate(ToIntTriFunction<A, B, C> matchWeighter) {
        return ruleContext.newRuleBuilder(matchWeighter);
    }

    public <Solution_> RuleBuilder<Solution_> andTerminate(ToLongTriFunction<A, B, C> matchWeighter) {
        return ruleContext.newRuleBuilder(matchWeighter);
    }

    public <Solution_> RuleBuilder<Solution_> andTerminate(TriFunction<A, B, C, BigDecimal> matchWeighter) {
        return ruleContext.newRuleBuilder(matchWeighter);
    }

    private <GroupKey_> ViewItem<?> buildGroupBy(Variable<GroupKey_> groupKey,
            Function3<A, B, C, GroupKey_> groupKeyExtractor, AccumulateFunction... accFunctions) {
        Variable<A> inputA = patternVariableA.getPrimaryVariable();
        Variable<B> inputB = patternVariableB.getPrimaryVariable();
        Variable<C> inputC = patternVariableC.getPrimaryVariable();
        ViewItem<?> innerGroupByPattern =
                joinViewItemsWithLogicalAnd(patternVariableA, patternVariableB, patternVariableC);
        return DSL.groupBy(innerGroupByPattern, inputA, inputB, inputC, groupKey, groupKeyExtractor, accFunctions);
    }

}
