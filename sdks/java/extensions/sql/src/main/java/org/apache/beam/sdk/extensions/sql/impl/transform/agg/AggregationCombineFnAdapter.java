/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.extensions.sql.impl.transform.agg;

import javax.annotation.Nullable;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.CoderRegistry;
import org.apache.beam.sdk.extensions.sql.impl.UdafImpl;
import org.apache.beam.sdk.extensions.sql.impl.transform.BeamBuiltinAggregations;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.schemas.SchemaCoder;
import org.apache.beam.sdk.transforms.Combine.CombineFn;
import org.apache.beam.sdk.values.Row;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.sql.validate.SqlUserDefinedAggFunction;

/** Wrapper {@link CombineFn}s for aggregation function calls. */
public class AggregationCombineFnAdapter<T> {
  private abstract static class WrappedCombinerBase<T> extends CombineFn<T, Object, Object> {
    CombineFn<T, Object, Object> combineFn;

    WrappedCombinerBase(CombineFn<T, Object, Object> combineFn) {
      this.combineFn = combineFn;
    }

    @Override
    public Object createAccumulator() {
      return combineFn.createAccumulator();
    }

    @Override
    public Object addInput(Object accumulator, T input) {
      T processedInput = getInput(input);
      return (processedInput == null)
          ? accumulator
          : combineFn.addInput(accumulator, getInput(input));
    }

    @Override
    public Object mergeAccumulators(Iterable<Object> accumulators) {
      return combineFn.mergeAccumulators(accumulators);
    }

    @Override
    public Object extractOutput(Object accumulator) {
      return combineFn.extractOutput(accumulator);
    }

    @Nullable
    abstract T getInput(T input);
  }

  private static class MultiInputCombiner extends WrappedCombinerBase<Row> {
    MultiInputCombiner(CombineFn<Row, Object, Object> combineFn) {
      super(combineFn);
    }

    @Override
    Row getInput(Row input) {
      for (Object o : input.getValues()) {
        if (o == null) {
          return null;
        }
      }
      return input;
    }
  }

  private static class SingleInputCombiner extends WrappedCombinerBase<Object> {
    SingleInputCombiner(CombineFn<Object, Object, Object> combineFn) {
      super(combineFn);
    }

    @Override
    Object getInput(Object input) {
      return input;
    }
  }

  private static class ConstantEmpty extends CombineFn<Row, Row, Row> {
    private static final Schema EMPTY_SCHEMA = Schema.builder().build();
    private static final Row EMPTY_ROW = Row.withSchema(EMPTY_SCHEMA).build();

    public static final ConstantEmpty INSTANCE = new ConstantEmpty();

    @Override
    public Row createAccumulator() {
      return EMPTY_ROW;
    }

    @Override
    public Row addInput(Row accumulator, Row input) {
      return EMPTY_ROW;
    }

    @Override
    public Row mergeAccumulators(Iterable<Row> accumulators) {
      return EMPTY_ROW;
    }

    @Override
    public Row extractOutput(Row accumulator) {
      return EMPTY_ROW;
    }

    @Nullable
    public Row getInput(Row input) {
      return EMPTY_ROW;
    }

    @Override
    public Coder<Row> getDefaultOutputCoder(CoderRegistry registry, Coder<Row> inputCoder) {
      return SchemaCoder.of(EMPTY_SCHEMA);
    }
  }

  /** Creates either a UDAF or a built-in {@link CombineFn}. */
  public static CombineFn<?, ?, ?> createCombineFn(
      AggregateCall call, Schema.Field field, String functionName) {
    CombineFn combineFn;
    if (call.getAggregation() instanceof SqlUserDefinedAggFunction) {
      combineFn = getUdafCombineFn(call);
    } else {
      combineFn = BeamBuiltinAggregations.create(functionName, field.getType().getTypeName());
    }
    if (call.getArgList().isEmpty()) {
      return new SingleInputCombiner(combineFn);
    } else if (call.getArgList().size() == 1) {
      return new SingleInputCombiner(combineFn);
    } else {
      return new MultiInputCombiner(combineFn);
    }
  }

  public static CombineFn<Row, ?, Row> createConstantCombineFn() {
    return ConstantEmpty.INSTANCE;
  }

  private static CombineFn<?, ?, ?> getUdafCombineFn(AggregateCall call) {
    try {
      return ((UdafImpl) ((SqlUserDefinedAggFunction) call.getAggregation()).function)
          .getCombineFn();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
